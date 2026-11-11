#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校园问卷系统 —— 长期运行故障率长稳测试（验收标准 3.2 / 硬性指标二）

与 loadtest.py 的区别：
  · loadtest.py 回答「100 个并发用户涌进来会不会垮」（峰值能力）
  · 本脚本回答「挂着跑一整天会不会慢慢坏掉」（长时间稳定性）

两者都必须过，因为它们的失败模式完全不同：
  峰值测试能抓到的（连接池耗尽）在低并发下根本不出现；
  而长稳测试能抓到的（内存泄漏、连接泄漏、日志撑爆磁盘、计数器溢出）
  在 5 分钟压测里连影子都看不到。

口径（对齐 docs/requirements/验收标准.md 3.2）：
  · 20 并发持续循环（不要 100，长稳测的是稳定性不是峰值）
  · 故障 = 5xx + 超时 + 连接拒绝；4xx 不计入
  · 额外采集 JVM 堆占用曲线与 GC 次数，用于证明「无内存泄漏」
  · 每小时内故障率必须 < 0.1%，而不是平均下来 < 0.1%
    —— 平均值会掩盖「前 23 小时好好的、最后 1 小时全崩」这种情况

用法：
  python deploy/test/soak.py --duration 900          # 15 分钟，验证脚本本身
  python deploy/test/soak.py                         # 正式 24 小时
  python deploy/test/soak.py --duration 86400 --concurrency 20
"""

import argparse
import asyncio
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import loadtest as lt  # noqa: E402  复用 prepare / cleanup / run_load / analyse / pct

JAVA_HOME = os.environ.get("JAVA_HOME", r"D:\huanjing\jdk-17")
JSTAT = os.path.join(JAVA_HOME, "bin", "jstat.exe")


def java_pid():
    """找到后端 JVM 的进程号。

    不去查端口映射（那要 netsh，还得解析输出），直接按命令行特征匹配 java 进程。
    后端有两种启动方式，命令行长得完全不一样，两个都要认：
      - 开发态：java -cp <依赖;target/classes> com.yjh.backend1.Backend1Application
      - 部署态：java -jar backend\target\backend1-0.0.1-SNAPSHOT.jar
    只匹配 Backend1Application 的话，jar 模式会返回 None，脚本会静默跳过 JVM 采样 ——
    而「无内存泄漏」这条结论正是靠采样数据支撑的，跳过等于报告缺证据。
    另外排除自己（soak.py 是 python 进程，理论上不会撞），只取第一个。
    """
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             " Where-Object { $_.CommandLine -like '*Backend1Application*' -or "
             " $_.CommandLine -like '*backend1-*.jar*' } | "
             " Select-Object -First 1).ProcessId"],
            capture_output=True, timeout=25,
        )
        text = out.stdout.decode("utf-8", "replace").strip()
        return int(text) if text.isdigit() else None
    except Exception:  # noqa: BLE001
        return None


def sample_jvm(pid):
    """采一次 JVM 状态：堆占用（%）与 GC 次数。

    用 jstat -gcutil 而不是 jmap/jcmd：前两者会触发 STW 或强制 Full GC，
    在长稳测试里采样本本身就会污染被测对象。jstat 是只读的。

    关键指标是 FGC（Full GC 次数）。FGC 长期为 0 说明对象都在年轻代被回收，
    没有「越攒越多」的迹象；如果 FGC 随时间线性增长且老年代占用不下降，
    那就是内存泄漏 —— 这也是「附加要求：提供内存占用随时间曲线」要证明的东西。
    """
    if not pid:
        return None
    try:
        out = subprocess.run([JSTAT, "-gcutil", str(pid)], capture_output=True, timeout=20)
        lines = out.stdout.decode("utf-8", "replace").strip().splitlines()
        if len(lines) < 2:
            return None
        header = lines[0].split()
        values = lines[1].split()
        row = dict(zip(header, values))
        return {
            "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "heap_pct": float(row.get("O", 0)),       # 老年代占用率
            "eden_pct": float(row.get("E", 0)),
            "ygc": int(row.get("YGC", 0)),
            "fgc": int(row.get("FGC", 0)),
            "gct_s": float(row.get("GCT", 0)),
        }
    except Exception:  # noqa: BLE001
        return None


async def run_soak(concurrency, duration, ramp_up, tokens, plan, interval, jvm_pid, samples,
                   think=0.0):
    """跑长稳测试，同时按 interval 采 JVM 样本。

    采样放在独立的协程里，不阻塞压测循环 —— 否则采样期间的压力会出现空洞，
    而长稳测试最忌讳的就是「时压时不压」，那样内存曲线也失去意义。
    """
    async def sampler():
        while True:
            await asyncio.sleep(interval)
            s = sample_jvm(jvm_pid)
            if s:
                samples.append(s)
                print(f"  [{s['at']}] 老年代 {s['heap_pct']:.1f}%  "
                      f"YGC {s['ygc']}  FGC {s['fgc']}  GC 累计 {s['gct_s']:.1f}s")

    task = asyncio.create_task(sampler())
    try:
        recorder, elapsed = await lt.run_load(concurrency, duration, ramp_up, tokens, plan, think)
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
    return recorder, elapsed


def hourly_breakdown(recorder, started_at, duration):
    """按小时切分故障率。

    只看总平均是不够的：24 小时里前 23 小时 0 故障、最后 1 小时 100% 故障，
    平均值仍然是 4.2%，但系统显然已经坏了。所以要逐小时判定。
    本函数按时间戳把这批请求分桶，桶宽 1 小时（短测时桶宽自动退化为若干分钟）。
    """
    if not recorder.stamps:
        return []
    width = 3600.0 if duration >= 3600 else max(60.0, duration / 10)
    buckets = {}
    for ts, fault, code in recorder.stamps:
        idx = int((ts - started_at) // width)
        b = buckets.setdefault(idx, {"total": 0, "faults": 0})
        b["total"] += 1
        if fault:
            b["faults"] += 1
    out = []
    for idx in sorted(buckets):
        b = buckets[idx]
        begin = started_at + idx * width
        rate = b["faults"] / b["total"] * 100 if b["total"] else 0.0
        out.append({
            "window": f"{datetime.fromtimestamp(begin):%H:%M:%S} 起 {int(width // 60)} 分钟",
            "total": b["total"],
            "faults": b["faults"],
            "rate_pct": round(rate, 4),
        })
    return out


def markdown(stat, checks, samples, hours, plan, started_at, finished_at, concurrency, think=0.0):
    L = []
    A = L.append
    A("# 长期运行故障率报告（长稳测试）\n")
    A(f"- 生成时间：{datetime.now():%Y-%m-%d %H:%M:%S}")
    A(f"- 压测时间：{started_at:%Y-%m-%d %H:%M:%S} — {finished_at:%Y-%m-%d %H:%M:%S}")
    A(f"- 持续时长：{(finished_at - started_at).total_seconds() / 3600:.2f} 小时")
    A(f"- 负载：{concurrency} 并发持续循环（查询 20% / 草稿 10% / 提交 70%）")
    if think > 0:
        A(f"- 思考时间：每轮之间随机停顿 {think:.0f} 秒 ×[0.5,1.5]（模拟真实用户节奏，"
          f"实测吞吐约 {stat['throughput_rps']} req/s）")
    A(f"- 压测问卷：#{plan['surveyId']} 《{plan['title']}》")
    A("- 工具：`deploy/test/soak.py`（复用 `loadtest.py` 的压测内核，另加 JVM 采样）\n")

    A("## 1. 结论\n")
    A("| 验收条件 | 结果 | 实测 |")
    A("| --- | --- | --- |")
    for name, ok, actual in checks:
        A(f"| {name} | {'✅ 通过' if ok else '❌ 未通过'} | {actual} |")
    A("")

    A("## 2. 总体指标\n")
    A("| 指标 | 数值 |")
    A("| --- | --- |")
    A(f"| 总请求数 | {stat['total_requests']} |")
    A(f"| 吞吐量 | {stat['throughput_rps']} req/s |")
    A(f"| 有效提交数 | {stat['success_submit']} |")
    A(f"| 平均响应 | {stat['avg_ms']} ms |")
    A(f"| P50 / P95 / P99 | {stat['p50_ms']} / {stat['p95_ms']} / {stat['p99_ms']} ms |")
    A(f"| 最大响应 | {stat['max_ms']} ms |")
    A(f"| 故障数 | {stat['faults']} |")
    A(f"| 故障率 | {stat['fault_rate_pct']}% |")
    A("")

    A("## 3. 分时段故障率\n")
    A("长时间运行不能只看总平均：前 23 小时 0 故障、最后 1 小时全崩，")
    A("平均值仍然「达标」。所以按时间桶逐段判定。\n")
    A("| 时间段 | 请求数 | 故障数 | 故障率 |")
    A("| --- | --- | --- | --- |")
    for h in hours:
        A(f"| {h['window']} | {h['total']} | {h['faults']} | {h['rate_pct']}% |")
    A("")

    A("## 4. JVM 内存与 GC 曲线\n")
    if samples:
        first, last = samples[0], samples[-1]
        A("| 采样时间 | 老年代 | YGC | FGC | GC 累计耗时 |")
        A("| --- | --- | --- | --- | --- |")
        for s in samples:
            A(f"| {s['at']} | {s['heap_pct']:.1f}% | {s['ygc']} | {s['fgc']} | {s['gct_s']:.1f}s |")
        A("")
        A(f"- 采样 {len(samples)} 次，老年代占用从 {first['heap_pct']:.1f}% 到 {last['heap_pct']:.1f}%")
        A(f"- 期间 Full GC 次数：{last['fgc'] - first['fgc']} 次"
          f"（总 {last['fgc']} 次，累计耗时 {last['gct_s']:.1f}s）")
        if last["fgc"] - first["fgc"] == 0:
            A("- **Full GC 全程为 0**：所有临时对象都在年轻代被回收，")
            A("  没有对象被长期持有 —— 这是「无内存泄漏」最直接的证据。")
        else:
            A("- 出现了 Full GC，需要结合老年代占用是否回落判断是否泄漏。")
        A("")
        A("> 采样用 `jstat -gcutil`（只读，不触发 GC）。")
        A("> 刻意不用 `jmap -histo` / `jcmd GC.heap_dump`：后者会 STW，采样本身会污染被测对象。")
        A("")
    else:
        A("未采集到 JVM 样本（找不到后端进程或 jstat 不可用）。\n")

    A("## 5. 复查清单\n")
    A("长稳测试期间除了故障率，还要确认这些「慢性病」没有发生：\n")
    A("- [ ] 服务未重启（`Backend1Application` 的启动日志只有一条）")
    A("- [ ] 无 OOM（日志中无 `OutOfMemoryError`）")
    A("- [ ] 无连接池耗尽（日志中无 `Connection is not available`）")
    A("- [ ] 数据库连接数没有单调增长（`SHOW STATUS LIKE 'Threads_connected'`）")
    A("- [ ] 日志文件没有撑爆磁盘（`logging.logback.rollingpolicy.max-total-size`）")
    A("")

    A("## 6. 复现方式\n")
    A("```powershell")
    A('$env:JAVA_HOME = "D:\\huanjing\\jdk-17"')
    A("python deploy/test/soak.py --duration 900      # 15 分钟自检")
    A("python deploy/test/soak.py                    # 正式 24 小时")
    A("```")
    A("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--concurrency", type=int, default=20,
                    help="长稳测试用低并发（默认 20），测的是稳定性不是峰值")
    ap.add_argument("--duration", type=int, default=86400, help="秒，默认 24 小时")
    ap.add_argument("--ramp-up", type=int, default=5)
    ap.add_argument("--interval", type=int, default=60, help="JVM 采样间隔（秒）")
    ap.add_argument("--think", type=float, default=5.0,
                    help="每轮之间的思考时间（秒，默认 5）。"
                         "不设的话 20 个虚拟用户会以机器速度狂写，24 小时灌进上千万份答卷")
    ap.add_argument("--users", type=int, default=0)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    print(f"长稳测试：{args.concurrency} 并发，{args.duration}s（{args.duration / 3600:.2f} 小时）")
    jvm_pid = java_pid()
    print(f"  后端 JVM 进程号：{jvm_pid or '未找到（将跳过 JVM 采样）'}")
    if not jvm_pid:
        print("  ！警告：没有 JVM 采样就无法证明「无内存泄漏」，本次报告该条会判未取证")

    print("准备压测数据 ...")
    admin_tok, teacher_tok, tokens, plan = lt.prepare(args.users or args.concurrency)
    print(f"  问卷 #{plan['surveyId']}《{plan['title']}》已发布")

    samples = []
    print("开始长稳测试 ...")
    started_at = datetime.now()
    try:
        recorder, elapsed = asyncio.run(
            run_soak(args.concurrency, args.duration, args.ramp_up, tokens, plan,
                     args.interval, jvm_pid, samples, args.think)
        )
    except KeyboardInterrupt:
        print("\n被中断，正在生成已有数据的报告 ...")
        recorder, elapsed = None, 0
    finished_at = datetime.now()

    if recorder is None:
        return 1

    stat = lt.analyse(recorder, elapsed, args.concurrency)
    hours = hourly_breakdown(recorder, started_at.timestamp(), elapsed)

    checks = []
    checks.append(("整体故障率 < 0.1%", stat["fault_rate_pct"] < 0.1, f"{stat['fault_rate_pct']}%"))
    worst = max((h["rate_pct"] for h in hours), default=0.0)
    checks.append(("每个时段故障率均 < 0.1%", worst < 0.1, f"最差时段 {worst}%"))
    checks.append(("无 5xx", not any(":5" in k for k in stat["status_breakdown"]), ""))
    if samples:
        delta = samples[-1]["fgc"] - samples[0]["fgc"]
        checks.append(("Full GC 次数为 0（无内存泄漏迹象）", delta == 0, f"{delta} 次"))
    else:
        checks.append(("Full GC 次数为 0（无内存泄漏迹象）", False, "未取证"))

    print("\n" + "=" * 56)
    for name, ok, actual in checks:
        print(f"  {'[PASS]' if ok else '[FAIL]'} {name} {actual}")
    print("=" * 56)
    print(f"  总请求 {stat['total_requests']}，吞吐 {stat['throughput_rps']} req/s")
    print(f"  故障 {stat['faults']} 个，故障率 {stat['fault_rate_pct']}%")

    if not args.keep:
        print("清理压测数据 ...")
        lt.cleanup(admin_tok, plan)

    os.makedirs("docs/test", exist_ok=True)
    with open("docs/test/soak-result.json", "w", encoding="utf-8") as f:
        json.dump({
            "plan": plan,
            "stat": stat,
            "checks": [{"name": n, "pass": o, "actual": a} for n, o, a in checks],
            "hours": hours,
            "samples": samples,
            "started_at": started_at.isoformat(timespec="seconds"),
            "finished_at": finished_at.isoformat(timespec="seconds"),
        }, f, ensure_ascii=False, indent=2)

    md = markdown(stat, checks, samples, hours, plan, started_at, finished_at, args.concurrency, args.think)
    with open("docs/test/长稳测试报告.md", "w", encoding="utf-8") as f:
        f.write(md)
    print("\n报告已写入 docs/test/长稳测试报告.md 与 docs/test/soak-result.json")

    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    sys.exit(main())
