#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校园问卷系统 —— 答卷并发压测（验收标准 3.1 / 硬性指标一）

背景：本机没有 JMeter / k6 / ab，但已有 Python 3.14 + aiohttp。
     所以用 asyncio 直接压，行为和 JMeter 的「线程组 + ramp-up + 循环时长」等价，
     产物是一份可复核的 JSON + Markdown 报告，而不是只打印一行数字。

压测口径（严格对齐 docs/requirements/验收标准.md 3.1）：
  · 并发 100，ramp-up 10s，持续 5 分钟
  · 混合比例：查询问卷 20% / 保存草稿 10% / 提交答卷 70%
  · 判定：提交接口 P95 < 3000ms、平均 < 1500ms
  · 故障率 =(5xx + 超时 + 连接拒绝) / 总请求，4xx 业务错误不计入故障

用法：
  python deploy/test/loadtest.py                  # 正式压测（100 并发 5 分钟）
  python deploy/test/loadtest.py --smoke          # 冒烟（10 并发 20 秒，验证脚本本身）
  python deploy/test/loadtest.py --concurrency 50 --duration 60
"""

import argparse
import asyncio
import json
import os
import random
import statistics
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime

try:
    import aiohttp
except ImportError:
    sys.exit("缺少 aiohttp：python -m pip install aiohttp")

BASE = os.environ.get("LOAD_BASE", "http://localhost:8080")
API = BASE + "/api"

# Windows 控制台默认 GBK，中文打印会变乱码；报告文件本身写 UTF-8，这里只是让屏幕可读
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

ADMIN = ("admin", "Admin@123")
TEACHER = ("teacher", "Teacher@123")
STUDENT = ("student", "Student@123")

# 混合比例（与验收标准一致）
MIX = [("query", 0.20), ("draft", 0.10), ("submit", 0.70)]

# 性能整改过程（写进报告第 8 节）。这些数字来自本项目的实际迭代记录，
# 换成别的机器会有差异，但每一步的「为什么」是可复用的。
STAGES = [
    ("首轮（单账号 + 长事务）", "19.8", "3458.5ms", "5305.0ms", "20.18%", "基线"),
    ("拆成两个事务（REQUIRES_NEW）", "16.3", "5709.8ms", "9568.8ms", "5.14%", "计数移出主事务（反而更慢）"),
    ("改为 100 个独立账号", "39.8", "2716.9ms", "3686.9ms", "0.00%", "修压测模型缺陷"),
    ("合并为 1 次 commit", "58.2", "1789.2ms", "1986.7ms", "0.00%", "3 commit → 1 commit"),
    ("关闭 SQL 日志 + INFO 级别", "211.0", "504.0ms", "654.9ms", "0.0036%", "去掉 System.out 串行化"),
    ("草稿死锁重试（治标）", "210.3", "495.1ms", "640.9ms", "0.00%", "语句级重试，死锁仍在"),
    ("删草稿移出提交事务（治本）", "204.4", "539.8ms", "673.9ms", "0.00%", "按主键删，不再有间隙锁"),
]


# ----------------------------------------------------------------------------
# 同步准备阶段（建卷 / 发布 / 登录拿 token），用 urllib 就够，避免另一套 async 样板
# ----------------------------------------------------------------------------
def call(method, path, body=None, token=None, raw=False):
    url = API + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            payload = r.read()
    except urllib.error.HTTPError as e:
        payload = e.read()
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {payload[:300].decode('utf-8', 'replace')}")
    if raw:
        return payload
    obj = json.loads(payload.decode("utf-8"))
    if obj.get("code") != 200:
        raise RuntimeError(f"{method} {path} -> {obj.get('code')} {obj.get('msg')}")
    return obj.get("data")


def login(username, password):
    return call("POST", "/auth/login", {"username": username, "password": password})["token"]


def prepare(user_count):
    """建一份压测专用问卷 + N 个压测学生账号。

    为什么必须用 N 个不同账号，而不是 100 个并发共用一个 student：
    2026-11-09 第一次压测就踩了这个坑。100 个虚拟用户共用一个账号时，
    survey_draft 里只有一行 (survey_id, user_id=3)，草稿保存（10%）要 X 锁这行、
    提交成功后删草稿（70%）也要 X 锁这行，于是 80% 的请求全挤在这一行上，
    和问卷计数器那一行一样成为热点，测出来的数字反映的是「一个人疯狂刷新」
    而不是「一个班同时在填同一份问卷」。真实场景里 100 个学生 = 100 行草稿，不存在这个竞争。

    为什么 limit_once=0（允许重复填写）：
    验收要求「提交接口」被压测 5 分钟，如果每个账号只能交一次，100 个账号只够 100 次请求，
    压不出任何东西。limit_once=0 让每个账号可以反复提交，才测得到插入吞吐。
    """
    admin_tok = login(*ADMIN)
    teacher_tok = login(*TEACHER)
    stamp = datetime.now().strftime("%m%d%H%M%S")

    survey = call(
        "POST",
        "/surveys",
        {
            "title": f"压测问卷 {stamp}",
            "description": "由 deploy/test/loadtest.py 自动创建，压测结束后删除",
            "category": "TEACHING",
            "anonymous": 0,
            "limitOnce": 0,
        },
        teacher_tok,
    )
    sid = survey["id"]

    questions = [
        {
            "questionType": "SINGLE",
            "title": "你对本课程的总体满意度？",
            "isRequired": 1,
            "options": [{"content": "非常满意"}, {"content": "满意"}, {"content": "一般"}, {"content": "不满意"}],
        },
        {
            "questionType": "MULTIPLE",
            "title": "你希望增加哪些教学形式？",
            "options": [{"content": "案例讨论"}, {"content": "实验实操"}, {"content": "小组汇报"}],
        },
        {"questionType": "TEXT", "title": "你的学号后四位", "maxLength": 20},
        {"questionType": "RATING", "title": "推荐学弟学妹选这门课吗？", "ratingMax": 5},
    ]
    detail = call("PUT", f"/surveys/{sid}/questions", {"questions": questions}, teacher_tok)

    # 取真实选项 ID —— 不能按下标猜，后端不保证 ID 连续
    qs = detail["questions"]
    single_opts = [o["id"] for o in qs[0]["options"]]
    multi_opts = [o["id"] for o in qs[1]["options"]]
    qids = [q["id"] for q in qs]

    call("POST", f"/surveys/{sid}/publish", None, teacher_tok)

    # 建压测学生账号（前缀 lt 便于清理，避免和真实学号混淆）
    print(f"  创建 {user_count} 个压测学生账号 ...")
    users = []
    for i in range(user_count):
        username = f"lt{stamp}{i:03d}"
        try:
            created = call(
                "POST",
                "/users",
                {
                    "username": username,
                    "realName": f"压测学生{i:03d}",
                    "password": "Load@2025",
                    "roleCode": "ROLE_STUDENT",
                    "department": "压测班级",
                },
                admin_tok,
            )
            users.append({"id": created["id"], "username": username})
        except Exception as e:  # noqa: BLE001
            print(f"  [warn] 建账号 {username} 失败：{e}")

    print(f"  登录 {len(users)} 个账号拿令牌 ...")
    tokens = []
    for u in users:
        try:
            tokens.append(login(u["username"], "Load@2025"))
        except Exception as e:  # noqa: BLE001
            print(f"  [warn] 登录 {u['username']} 失败：{e}")
    if not tokens:
        raise RuntimeError("一个压测账号都没登录成功，无法继续")
    print(f"  {len(tokens)} 个令牌就绪")

    plan = {
        "surveyId": sid,
        "title": survey["title"],
        "questionIds": qids,
        "singleOptions": single_opts,
        "multiOptions": multi_opts,
        "userIds": [u["id"] for u in users],
    }
    return admin_tok, teacher_tok, tokens, plan


def cleanup(admin_tok, plan):
    """清理：问卷软删 + 压测账号删除。账号必须删，否则每跑一次库里多 100 个用户。"""
    ok = True
    try:
        call("DELETE", f"/surveys/{plan['surveyId']}?confirm=true", None, admin_tok)
    except Exception as e:  # noqa: BLE001
        print(f"  [warn] 删除问卷失败：{e}")
        ok = False
    removed = 0
    for uid in plan.get("userIds", []):
        try:
            call("DELETE", f"/users/{uid}", None, admin_tok)
            removed += 1
        except Exception:  # noqa: BLE001
            pass
    print(f"  已删除压测账号 {removed}/{len(plan.get('userIds', []))} 个")
    return ok


def count_sheets(plan):
    """数一下这张问卷下有多少条有效答卷，用于核对「记录数 = 成功提交数」。"""
    try:
        summary = call("GET", f"/surveys/{plan['surveyId']}/answer-summary", None, login(*TEACHER))
        return summary
    except Exception as e:  # noqa: BLE001
        print(f"  [warn] 取回收概览失败：{e}")
        return None


# ----------------------------------------------------------------------------
# 异步压测阶段
# ----------------------------------------------------------------------------
class Recorder:
    def __init__(self):
        self.latencies = defaultdict(list)   # endpoint -> [ms]
        self.status = Counter()              # (endpoint, code) -> n
        self.errors = Counter()              # 错误类型 -> n
        self.error_samples = []              # 前 5 条真实错误信息
        self.success_submit = 0
        # (时间戳, 是否故障, 状态码) —— soak.py 要按时间段切分故障率，
        # 光有计数没法回答「是前 23 小时坏的还是最后 1 小时坏的」
        self.stamps = []

    def add(self, endpoint, ms, code, err=None):
        self.latencies[endpoint].append(ms)
        self.status[(endpoint, code)] += 1
        self.stamps.append((time.time(), code >= 500 or code < 0, code))
        if err:
            self.errors[err] += 1
            if len(self.error_samples) < 5:
                self.error_samples.append(f"{endpoint}: {err}")
        if endpoint == "submit" and code == 200:
            self.success_submit += 1


async def worker(idx, session, token, plan, deadline, recorder, ramp_delay, think=0.0):
    """一个虚拟用户：启动时先睡 ramp_delay 实现 ramp-up，然后循环到 deadline。

    think > 0 时每轮之间随机停顿 think*[0.5,1.5] 秒，模拟真实用户「看一眼再填」。
    长稳测试必须带 think，否则 20 个虚拟用户会以 200+ req/s 的机器速度狂写，
    24 小时能灌进上千万份答卷 —— 测的是压力，不是稳定性，还会把库撑爆。
    """
    if ramp_delay > 0:
        await asyncio.sleep(ramp_delay)

    headers = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    sid = plan["surveyId"]
    qids = plan["questionIds"]

    # 每轮按权重抽一个动作
    keys = [k for k, _ in MIX]
    weights = [w for _, w in MIX]

    while time.perf_counter() < deadline:
        action = random.choices(keys, weights=weights, k=1)[0]

        if action == "query":
            url, method, body, ep = f"{API}/hall?page=1&size=10", "GET", None, "query"
        elif action == "draft":
            # 草稿每次覆盖写同一行（uk_survey_user），反复压 upsert 路径
            body = {
                "answers": [
                    {"questionId": qids[0], "optionValues": [random.choice(plan["singleOptions"])]},
                    {"questionId": qids[1], "optionValues": random.sample(plan["multiOptions"], k=random.randint(1, 2))},
                ]
            }
            url, method, body, ep = f"{API}/hall/surveys/{sid}/draft", "PUT", body, "draft"
        else:
            body = {
                "answers": [
                    {"questionId": qids[0], "optionValues": [random.choice(plan["singleOptions"])]},
                    {"questionId": qids[1], "optionValues": random.sample(plan["multiOptions"], k=random.randint(1, 3))},
                    {"questionId": qids[2], "textValue": f"u{idx:03d}"},
                    {"questionId": qids[3], "numberValue": random.randint(1, 5)},
                ]
            }
            url, method, body, ep = f"{API}/hall/surveys/{sid}/submit", "POST", body, "submit"

        t0 = time.perf_counter()
        try:
            async with session.request(
                method, url, json=body, headers=headers, timeout=aiohttp.ClientTimeout(total=15)
            ) as resp:
                text = await resp.text()
                ms = (time.perf_counter() - t0) * 1000
                if resp.status == 200:
                    recorder.add(ep, ms, 200)
                elif 400 <= resp.status < 500:
                    # 4xx 是业务错误，按验收口径不计入故障，但要单独统计，否则会被藏起来
                    recorder.add(ep, ms, resp.status)
                    try:
                        msg = json.loads(text).get("msg", "")[:60]
                    except Exception:  # noqa: BLE001
                        msg = text[:60]
                    recorder.errors[f"HTTP {resp.status} {msg}"] += 1
                else:
                    recorder.add(ep, ms, resp.status, err=f"HTTP {resp.status}")
        except asyncio.TimeoutError:
            recorder.add(ep, (time.perf_counter() - t0) * 1000, -1, err="timeout")
        except aiohttp.ClientConnectorError:
            recorder.add(ep, (time.perf_counter() - t0) * 1000, -2, err="connection_refused")
        except aiohttp.ServerDisconnectedError:
            recorder.add(ep, (time.perf_counter() - t0) * 1000, -3, err="server_disconnected")
        except Exception as e:  # noqa: BLE001
            recorder.add(ep, (time.perf_counter() - t0) * 1000, -9, err=type(e).__name__)

        if think > 0:
            await asyncio.sleep(think * random.uniform(0.5, 1.5))


async def run_load(concurrency, duration, ramp_up, tokens, plan, think=0.0):
    recorder = Recorder()
    connector = aiohttp.TCPConnector(limit=0, ttl_dns_cache=300, force_close=False)
    timeout = aiohttp.ClientTimeout(total=15)

    start = time.perf_counter()
    deadline = start + ramp_up + duration

    async with aiohttp.ClientSession(connector=connector, timeout=timeout) as session:
        tasks = []
        for i in range(concurrency):
            # ramp-up：第 i 个用户在 ramp_up * i / concurrency 秒时启动
            delay = ramp_up * i / concurrency if concurrency > 0 else 0
            # 每个虚拟用户绑定一个独立账号；账号数少于并发数时才复用
            token = tokens[i % len(tokens)]
            tasks.append(asyncio.create_task(worker(i, session, token, plan, deadline, recorder, delay, think)))
        await asyncio.gather(*tasks)

    elapsed = time.perf_counter() - start
    return recorder, elapsed


# ----------------------------------------------------------------------------
# 报告
# ----------------------------------------------------------------------------
def pct(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    if lo == hi:
        return ordered[lo]
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def analyse(recorder, elapsed, concurrency):
    total = sum(len(v) for v in recorder.latencies.values())
    all_lat = [x for v in recorder.latencies.values() for x in v]

    # 故障 = 5xx + 超时 + 连接拒绝 + 服务端断开（4xx 业务错误按验收口径不计）
    faults = sum(n for (_, code), n in recorder.status.items() if code >= 500 or code < 0)
    fault_rate = (faults / total * 100) if total else 0.0

    by_ep = {}
    for ep, lat in recorder.latencies.items():
        ok = sum(n for (e, code), n in recorder.status.items() if e == ep and code == 200)
        by_ep[ep] = {
            "count": len(lat),
            "ok": ok,
            "avg_ms": round(statistics.fmean(lat), 1) if lat else 0,
            "p50_ms": round(pct(lat, 0.50), 1),
            "p90_ms": round(pct(lat, 0.90), 1),
            "p95_ms": round(pct(lat, 0.95), 1),
            "p99_ms": round(pct(lat, 0.99), 1),
            "max_ms": round(max(lat), 1) if lat else 0,
        }

    return {
        "concurrency": concurrency,
        "duration_s": round(elapsed, 1),
        "total_requests": total,
        "throughput_rps": round(total / elapsed, 1) if elapsed else 0,
        "avg_ms": round(statistics.fmean(all_lat), 1) if all_lat else 0,
        "p50_ms": round(pct(all_lat, 0.50), 1),
        "p95_ms": round(pct(all_lat, 0.95), 1),
        "p99_ms": round(pct(all_lat, 0.99), 1),
        "max_ms": round(max(all_lat), 1) if all_lat else 0,
        "faults": faults,
        "fault_rate_pct": round(fault_rate, 4),
        "status_breakdown": {f"{ep}:{code}": n for (ep, code), n in sorted(recorder.status.items())},
        "by_endpoint": by_ep,
        "error_kinds": dict(recorder.errors.most_common(10)),
        "error_samples": recorder.error_samples,
        "success_submit": recorder.success_submit,
    }


def verdict(stat, sheet_info=None, deadlock=None):
    """逐条对照验收标准 3.1 的 6 个通过条件。

    前 4 条只看压测器自己采到的数据；后 2 条要看服务端与数据库，
    所以由 main() 采集后传进来（取不到时给 None，判定里明确写「未取证」而不是默认通过）。
    """
    submit = stat["by_endpoint"].get("submit", {})
    checks = []
    checks.append(("提交接口 P95 < 3000ms", submit.get("p95_ms", 0) < 3000, f"{submit.get('p95_ms', 0)}ms"))
    checks.append(("提交接口平均 < 1500ms", submit.get("avg_ms", 0) < 1500, f"{submit.get('avg_ms', 0)}ms"))
    checks.append(("整体故障率 < 0.1%", stat["fault_rate_pct"] < 0.1, f"{stat['fault_rate_pct']}%"))
    checks.append(("无 5xx", not any(":5" in k or k.endswith(":-1") or k.endswith(":-2") for k in stat["status_breakdown"]), ""))

    if sheet_info is None:
        checks.append(("答卷记录数 = 成功提交数", False, "未取证"))
    else:
        same = sheet_info.get("total") == stat["success_submit"]
        checks.append(("答卷记录数 = 成功提交数", same,
                       f"{stat['success_submit']} = {sheet_info.get('total')}"))

    if deadlock is None:
        checks.append(("压测期间无新死锁", False, "未取证"))
    else:
        checks.append(("压测期间无新死锁", not deadlock.get("new_deadlock"),
                       deadlock.get("detail", "")))
    return checks


def probe_deadlock(started_at):
    """读 MySQL 的最近一次死锁时间，用于判断本轮压测有没有产生新死锁。

    SHOW ENGINE INNODB STATUS 里的 LATEST DETECTED DEADLOCK 是「历史最近一次」，
    只要它的时间早于本轮压测开始时间，就说明这 5 分钟里没有新死锁。
    连不上数据库时返回 None —— 判定会写「未取证」，不会假装通过。

    :param started_at: 本轮压测开始时刻（datetime），用于比较
    """
    try:
        import subprocess

        mysql = os.environ.get(
            "MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
        )
        # 密码走 MYSQL_PWD 环境变量而不是 -p 参数：
        # 拼在命令行上会让密码出现在进程列表里（同机其他用户可见），
        # 而且密码含 # 时会被 shell 当注释截断。
        env = dict(os.environ)
        env["MYSQL_PWD"] = os.environ.get("DB_PASSWORD", "")
        out = subprocess.run(
            [mysql, "-u", os.environ.get("DB_USERNAME", "root"),
             "-e", "SHOW ENGINE INNODB STATUS\\G"],
            capture_output=True, timeout=20, env=env,
        )
        text = out.stdout.decode("utf-8", "replace")
        marker = "LATEST DETECTED DEADLOCK"
        if marker not in text:
            return {"new_deadlock": False, "detail": "InnoDB 无死锁记录", "at": None}
        seg = text.split(marker, 1)[1]
        for line in seg.splitlines()[1:6]:
            line = line.strip()
            if len(line) > 10 and line[:2] == "20":
                try:
                    when = datetime.strptime(line[:19], "%Y-%m-%d %H:%M:%S")
                except ValueError:
                    return {"new_deadlock": None, "detail": f"死锁时间无法解析：{line}", "at": line}
                if when >= started_at:
                    return {"new_deadlock": True, "detail": f"本轮压测期间发生死锁：{line}", "at": line}
                return {"new_deadlock": False,
                        "detail": f"最近一次死锁在 {line}，早于本轮压测开始时间", "at": line}
        return {"new_deadlock": None, "detail": "有死锁记录但未解析出时间", "at": None}
    except Exception as e:  # noqa: BLE001
        print(f"  （读取 InnoDB 死锁信息失败：{e}）")
        return None


def markdown(stat, checks, plan, sheet_info, machine, started_at=None, finished_at=None, deadlock=None):
    lines = []
    L = lines.append
    L("# 答卷并发压测报告\n")
    L(f"- 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    L(f"- 被测服务：{BASE}")
    L(f"- 压测问卷：#{plan['surveyId']} 《{plan['title']}》（实名、可重复填写）")
    L(f"- 压测工具：`deploy/test/loadtest.py`（Python asyncio + aiohttp）")
    L(f"- 运行环境：{machine}")
    if started_at:
        L(f"- 压测时间：{started_at:%Y-%m-%d %H:%M:%S} — {finished_at:%Y-%m-%d %H:%M:%S}")
    L(f"- 负载模型：{stat['concurrency']} 并发，ramp-up 10s，持续 {stat['duration_s']}s")
    L(f"- 混合比例：查询问卷 20% / 保存草稿 10% / 提交答卷 70%\n")

    L("## 1. 结论\n")
    L("| 验收条件 | 结果 | 实测 |")
    L("| --- | --- | --- |")
    for name, ok, actual in checks:
        L(f"| {name} | {'✅ 通过' if ok else '❌ 未通过'} | {actual} |")
    L("")
    all_pass = all(ok for _, ok, _ in checks)
    L(f"**总体判定：{'通过' if all_pass else '未通过'}**\n")

    L("## 2. 总体指标\n")
    L("| 指标 | 数值 |")
    L("| --- | --- |")
    L(f"| 总请求数 | {stat['total_requests']} |")
    L(f"| 吞吐量 | {stat['throughput_rps']} req/s |")
    L(f"| 有效提交成功数 | {stat['success_submit']} |")
    L(f"| 平均响应 | {stat['avg_ms']} ms |")
    L(f"| P50 / P95 / P99 | {stat['p50_ms']} / {stat['p95_ms']} / {stat['p99_ms']} ms |")
    L(f"| 最大响应 | {stat['max_ms']} ms |")
    L(f"| 故障数（5xx+超时+连接拒绝） | {stat['faults']} |")
    L(f"| 故障率 | {stat['fault_rate_pct']}% |")
    L("")

    L("## 3. 分接口指标\n")
    L("| 接口 | 请求数 | 成功 | 平均 | P50 | P95 | P99 | 最大 |")
    L("| --- | --- | --- | --- | --- | --- | --- | --- |")
    names = {"query": "GET /api/hall（查询问卷）", "draft": "PUT .../draft（保存草稿）", "submit": "POST .../submit（提交答卷）"}
    for ep in ("query", "draft", "submit"):
        d = stat["by_endpoint"].get(ep)
        if not d:
            continue
        L(f"| {names[ep]} | {d['count']} | {d['ok']} | {d['avg_ms']} | {d['p50_ms']} | {d['p95_ms']} | {d['p99_ms']} | {d['max_ms']} |")
    L("")

    L("## 4. 状态码分布\n")
    L("| 接口:状态码 | 次数 |")
    L("| --- | --- |")
    for k, v in stat["status_breakdown"].items():
        L(f"| {k} | {v} |")
    L("")
    L("> 状态码 -1 表示超时，-2 表示连接拒绝，-3 表示服务端断连。\n")

    L("## 5. 错误明细\n")
    if stat["error_kinds"]:
        L("| 错误 | 次数 |")
        L("| --- | --- |")
        for k, v in stat["error_kinds"].items():
            L(f"| {k} | {v} |")
        L("")
        if stat["error_samples"]:
            L("样本：\n")
            for s in stat["error_samples"]:
                L(f"- `{s}`")
            L("")
    else:
        L("无。\n")

    L("## 6. 数据一致性\n")
    if sheet_info:
        L(f"- 压测期间提交成功：**{stat['success_submit']}** 份")
        L(f"- 压测后回收概览：已回收 {sheet_info.get('total')} 份，有效 {sheet_info.get('validCount')} 份")
        L(f"- 一致：{'✅ 是' if sheet_info.get('total') == stat['success_submit'] else '❌ 否'}\n")
    else:
        L("- 未能取得回收概览（不影响主指标判定）\n")

    L("## 7. 复现方式\n")
    L("```powershell")
    L("# 1. 启动后端")
    L('$env:JAVA_HOME = "D:\\huanjing\\jdk-17"')
    L("$cp = (Get-Content backend\\target\\cp.txt -Raw).Trim() + ';' + (Resolve-Path backend\\target\\classes)")
    L('Start-Process "$env:JAVA_HOME\\bin\\java.exe" -ArgumentList @("-cp", $cp, "com.yjh.backend1.Backend1Application")')
    L("")
    L("# 2. 压测（脚本自建问卷，结束后自动删除）")
    L("python deploy/test/loadtest.py")
    L("```\n")

    L("## 8. 性能整改过程\n")
    L("首轮压测并未一次通过。下面这张表是本项目从「20% 故障率」走到「全部达标」的完整过程，")
    L("每一步都对应一个真实的瓶颈，而不是反复调大参数。\n")
    L("| 阶段 | 吞吐 | submit avg | submit P95 | 故障率 | 主要改动 |")
    L("| --- | --- | --- | --- | --- | --- |")
    for row in STAGES:
        L("| " + " | ".join(row) + " |")
    L("")
    L("每一轮的诊断依据：\n")
    L("1. **首轮 19.8 req/s、20.18% 故障** —— Hikari `maximum-pool-size: 20` 被长事务占满，")
    L("   连 `/api/hall` 这种只读请求都拿不到连接，`AuthUserLoader` 查用户直接抛")
    L("   `CannotCreateTransactionException`，前端 15s 超时。长事务里含热点行")
    L("   `UPDATE survey SET answer_count = ...`，100 并发全在同一行排队。")
    L("2. **拆分事务反而更慢（16.3 req/s）** —— 这是本轮最有价值的反例：把一个事务拆成")
    L("   「答卷入库 + 计数更新」两个事务，锁在第一个事务释放、第二个事务再拿回，")
    L("   而 InnoDB 的行锁本来就要持有到提交成功，于是白付一次 fsync，锁持有总时长并没变短。")
    L("   教训：**热点行锁的成本由提交次数决定，拆分事务只会增加提交次数。**")
    L("3. **发现压测模型缺陷** —— 100 个虚拟用户共用一个 student 账号，")
    L("   `survey_draft` 只有一行 `(survey_id, user_id)`，草稿保存与提交后删草稿锁同一行。")
    L("   真实场景里 100 个学生是 100 行草稿。改为 100 个独立压测账号后故障率降到 0。")
    L("   这一步说明：压测器本身的建模错误会被误读成产品缺陷。")
    L("4. **数据库基准测试定位硬瓶颈** —— 用 pymysql 直接压热点行，")
    L("   `UPDATE ... WHERE id=?` + commit 单线程只有 113.8 次/秒，")
    L("   多线程吞吐锁死在约 170 次/秒、延迟随线程线性增长 ——")
    L("   这是「每次 commit 等一次磁盘 fsync（约 6ms）」且 group commit 未生效的典型曲线。")
    L("   由此推出：一次提交付 3 次 commit（答卷/计数/日志）的理论上限是 170÷3≈57 次/秒，")
    L("   与实测 28 次/秒吻合。合并成 1 个事务后升到 58.2 req/s，验证了推算。")
    L("5. **发现配置级串行化点** —— `run.log` 在 7655 个请求后已达 68.2 MB")
    L("   （约 9KB/请求）。`mybatis-plus.log-impl: StdOutImpl` 直接写 `System.out`，")
    L("   同步无缓冲，每请求十几条 SQL 就是十几行输出，**输出锁本身成了全局串行点**。")
    L("   改成 `NoLoggingImpl` + `logging.level: INFO` 后，吞吐从 58 涨到 211 req/s ——")
    L("   单这一项就是 3.6 倍。这是本项目最大的性能教训：诊断日志开在压测环境里，")
    L("   测的是日志框架而不是业务代码。")
    L("6. **草稿死锁：先治标，再治本** —— 这是本项目最有代表性的一段。")
    L("")
    L("   现象：最后 1 个 500 来自 `survey_draft`，报错是 `Deadlock found when trying to get lock`，")
    L("   出错语句正是草稿的 `INSERT ... ON DUPLICATE KEY UPDATE`。")
    L("")
    L("   第一步（治标）：改成语句级重试（3 次、递增退避），前提是 `saveDraft` 不能包")
    L("   `@Transactional` —— 包了事务时死锁回滚的是整个事务，语句级重试必然继续失败。")
    L("   这一步之后 500 消失了，指标也达标了。")
    L("")
    L("   但**重试不等于问题消失**。第 6 条验收条件是「MySQL 无死锁日志」，")
    L("   再跑一轮就发现 InnoDB 仍在记录新的死锁 —— 只是每次都被重试吃掉了。")
    L("   把 5 分钟的压测日志交给验收方，人家一查 `SHOW ENGINE INNODB STATUS` 就能看见，")
    L("   属于「指标达标但问题仍在」，不能算通过。")
    L("")
    L("   第二步（治本）：读死锁明细才发现，冲突双方**根本不是同一行**。")
    L("   提交流程的 `DELETE ... WHERE survey_id=? AND user_id=?` 在唯一索引上取 next-key 锁")
    L("   （记录锁 + 前面的间隙），并发的 upsert 要对同一段间隙取插入意向锁，两者互斥；")
    L("   100 个学生同时填同一份问卷时，(159,559)、(159,560)… 这些相邻记录天天撞在一起。")
    L("   改法有两条，都很朴素：")
    L("   - 把删草稿移出提交事务（它本来就不属于「提交成功」的语义，只是顺手清理）；")
    L("   - 先查主键再 `deleteById`，只锁那一行，不碰间隙。")
    L("   改完再跑 5 分钟，InnoDB 的 `LATEST DETECTED DEADLOCK` 时间戳纹丝不动。")
    L("")
    L("   复盘：**把并发缺陷交给重试去兜，指标会好看，但问题只是从「用户可见」变成「日志可见」。**")
    L("   验收条件之所以写「无死锁日志」而不是「无 500」，就是为了防这一手。\n")

    L("### 未采纳的调参\n")
    L("基准测试中试过 `innodb_flush_log_at_trx_commit=2` + `sync_binlog=0`，")
    L("热点行吞吐立刻从 170 涨到 2639 次/秒。**但没有采纳**：")
    L("这是拿崩溃持久性换吞吐（掉电可能丢最近 1 秒已提交事务），")
    L("用改数据库配置的方式让压测通过，等于把风险留给上线后的真实用户。")
    L("实验后已立即还原为 `=1` / `=1`。\n")

    if deadlock:
        L("## 9. 数据库死锁取证\n")
        L(f"- 本轮压测开始时间：{started_at:%Y-%m-%d %H:%M:%S}")
        if deadlock.get("at"):
            L(f"- InnoDB 最近一次死锁：{deadlock['at']}")
        L(f"- 判定：{'❌ 本轮产生了新死锁' if deadlock.get('new_deadlock') else '✅ 本轮无新死锁'}")
        L(f"- 依据：{deadlock.get('detail', '')}\n")

    L("## 10. 结论\n")
    if all_pass:
        L("在「100 并发、ramp-up 10s、持续 5 分钟、查询 20% / 草稿 10% / 提交 70%」的负载下，")
        L(f"提交接口 P95 {stat['by_endpoint'].get('submit', {}).get('p95_ms')}ms（阈值 3000ms）、")
        L(f"平均 {stat['by_endpoint'].get('submit', {}).get('avg_ms')}ms（阈值 1500ms），")
        L(f"整体故障率 {stat['fault_rate_pct']}%，答卷记录数与成功提交数一致，")
        L("SRS 两条硬性指标均已满足。\n")
        L("### 关于压测工具（与验收标准的偏差说明）\n")
        L("`验收标准.md` D-08 写的是「含 JMeter 报告与结论」。本项目没有使用 JMeter，")
        L("用的是自研的 `deploy/test/loadtest.py`（Python asyncio + aiohttp）。这个偏差是有意为之：\n")
        L("1. **本机没有 JMeter**。一份靠记忆复现的「JMeter 报告」不可执行；")
        L("   而这份报告里的每个数字都能用仓库里的脚本重跑出来。")
        L("2. **需要登录态与造数**。100 个压测账号、一份发布的问卷、真实选项 id，")
        L("   用 JMeter 要写一堆前置采样器或 CSV；Python 里 `prepare()` 十行搞定，还能在 `cleanup()` 里删干净。")
        L("3. **第 5、6 条判定 JMeter 给不了**。「答卷记录数 = 成功提交数」要连库查，")
        L("   「本轮是否产生新死锁」要读 `SHOW ENGINE INNODB STATUS` 比对时间戳——")
        L("   这两条恰是验收里最容易造假的，所以做成了脚本内置取证（取不到证据判「未取证」，不默认通过）。\n")
        L("口径与 JMeter 一致：并发数、ramp-up、P50/P95/P99（线性插值）、")
        L("故障率只算 5xx/超时/连接拒绝（4xx 不计）。差别只在工具本身，不在度量方式。")
        L("**答辩时如实说明，不要声称用过 JMeter。**\n")
        L("需要说明的边界：")
        L(f"- 测试环境是单机（应用与 MySQL 同机、{stat['concurrency']} 个压测账号），")
        L("  不代表多实例部署或更大数据量下的表现；")
        L(f"- 压测问卷设置为「可重复填写」（`limit_once=0`），否则 {stat['concurrency']} 个账号只够交 ")
        L(f"{stat['concurrency']} 次，压不出持续吞吐；")
        L("- 压测期间数据库 `innodb_flush_log_at_trx_commit=1`、`sync_binlog=1` 未改动，")
        L("  即结果是在完整持久性保证下取得的。")
        L("- 死锁是<b>结构性消除</b>的，不是靠重试兜住的：第 9 节的时间戳证明本轮")
        L("  5 分钟内 InnoDB 没有记录到任何新死锁。\n")
    else:
        L("存在未通过的验收条件，详见第 1 节。\n")

    return "\n".join(lines)


def regen_report():
    """按已保存的 loadtest-result.json 重新生成 Markdown 报告。

    存在的意义：报告里的分析文字（第 8~10 节）是人工写的，改措辞、补结论时
    不应该为了刷一份文档再压 5 分钟。原始数据在 JSON 里，重算出来的指标与当时一致。
    """
    path = "docs/test/loadtest-result.json"
    if not os.path.exists(path):
        print(f"找不到 {path}，请先跑一次压测")
        return 1
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    plan = data["plan"]
    stat = data["stat"]
    sheet_info = data.get("sheet")
    deadlock = data.get("deadlock")
    started_at = datetime.fromisoformat(data["started_at"]) if data.get("started_at") else None
    finished_at = datetime.fromisoformat(data["finished_at"]) if data.get("finished_at") else None
    checks = [(c["name"], c["pass"], c["actual"]) for c in data["checks"]]
    md = markdown(stat, checks, plan, sheet_info, "Windows 11 / JDK 17 / MySQL 8.0 本机",
                  started_at, finished_at, deadlock)
    with open("docs/test/压测报告.md", "w", encoding="utf-8") as f:
        f.write(md)
    print(f"已按 {path} 重新生成 docs/test/压测报告.md")
    return 0 if all(ok for _, ok, _ in checks) else 1


# ----------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--concurrency", type=int, default=100)
    ap.add_argument("--duration", type=int, default=300, help="秒")
    ap.add_argument("--ramp-up", type=int, default=10, help="秒")
    ap.add_argument("--users", type=int, default=0, help="压测账号数，默认等于并发数")
    ap.add_argument("--smoke", action="store_true", help="10 并发 20 秒，验证脚本本身")
    ap.add_argument("--keep", action="store_true", help="压测后保留问卷与账号，便于手工查看")
    ap.add_argument("--report-only", action="store_true",
                    help="只按 docs/test/loadtest-result.json 重新生成报告，不重新压测")
    args = ap.parse_args()

    if args.report_only:
        return regen_report()

    if args.smoke:
        args.concurrency, args.duration, args.ramp_up = 10, 20, 3

    print(f"目标 {BASE}，{args.concurrency} 并发，ramp-up {args.ramp_up}s，持续 {args.duration}s")
    print("准备压测数据 ...")
    admin_tok, teacher_tok, tokens, plan = prepare(args.users or args.concurrency)
    print(f"  问卷 #{plan['surveyId']}《{plan['title']}》已发布，4 道题")
    before = count_sheets(plan)
    if before:
        print(f"  压测前已有 {before.get('total')} 份答卷")

    print("开始压测 ...")
    started_at = datetime.now()
    recorder, elapsed = asyncio.run(run_load(args.concurrency, args.duration, args.ramp_up, tokens, plan))
    finished_at = datetime.now()

    stat = analyse(recorder, elapsed, args.concurrency)

    print("\n回收概览核对 ...")
    sheet_info = count_sheets(plan)
    if sheet_info:
        ok = sheet_info.get("total") == stat["success_submit"]
        print(f"  提交成功 {stat['success_submit']}，库中 {sheet_info.get('total')} 份，"
              f"{'一致' if ok else '不一致（需排查）'}")

    print("\n数据库死锁核对 ...")
    deadlock = probe_deadlock(started_at)
    if deadlock:
        print(f"  {deadlock['detail']}")

    # 6 条验收条件必须等取证齐全后再判定，否则后两条会永远显示「未取证」
    checks = verdict(stat, sheet_info, deadlock)
    print("\n" + "=" * 56)
    for name, ok, actual in checks:
        print(f"  {'[PASS]' if ok else '[FAIL]'} {name} {actual}")
    print("=" * 56)
    print(f"  总请求 {stat['total_requests']}，吞吐 {stat['throughput_rps']} req/s")
    submit = stat["by_endpoint"].get("submit", {})
    print(f"  提交接口 avg={submit.get('avg_ms')}ms p95={submit.get('p95_ms')}ms p99={submit.get('p99_ms')}ms")
    print(f"  故障 {stat['faults']} 个，故障率 {stat['fault_rate_pct']}%")

    if not args.keep:
        print("清理压测问卷 ...")
        cleanup(admin_tok, plan)

    os.makedirs("docs/test", exist_ok=True)
    st_path = "docs/test/loadtest-result.json"
    with open(st_path, "w", encoding="utf-8") as f:
        json.dump(
            {"plan": plan, "stat": stat, "checks": [{"name": n, "pass": o, "actual": a} for n, o, a in checks],
             "sheet": sheet_info, "deadlock": deadlock,
             "started_at": started_at.isoformat(timespec="seconds"),
             "finished_at": finished_at.isoformat(timespec="seconds"),
             "at": datetime.now().isoformat(timespec="seconds")},
            f, ensure_ascii=False, indent=2,
        )
    md = markdown(stat, checks, plan, sheet_info, "Windows 11 / JDK 17 / MySQL 8.0 本机",
                  started_at, finished_at, deadlock)
    md_path = "docs/test/压测报告.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(md)
    print(f"\n报告已写入 {md_path} 与 {st_path}")

    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    sys.exit(main())
