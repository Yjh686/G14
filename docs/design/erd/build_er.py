# -*- coding: utf-8 -*-
"""根据 schema.json 渲染 ER 图 PNG：全局总览 + 三个分模块。

分模块图只保留有辨识价值的字段（主键 / 外键 / 关键业务列），
完整字段清单由 Word 文档中的「数据字典」逐表给出。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from er_lib import Diagram  # noqa: E402

BASE = Path(r"D:\G14(1)\docs\design\erd")
OUT = BASE / "assets"
OUT.mkdir(parents=True, exist_ok=True)
S = json.loads((BASE / "schema.json").read_text(encoding="utf-8"))

C_AUTH = "#3d6b96"
C_LOG = "#67757f"
C_SUR = "#2f7a6b"
C_ANS = "#8a5f2b"
C_WEAK = "#8a5f2b"
C_GRAY = "#b9c4ce"


def st(t):
    return (t.replace("BIGINT UNSIGNED", "BIGINT")
             .replace("TINYINT UNSIGNED", "TINYINT")
             .replace("INT UNSIGNED", "INT")
             .replace("GENERATED ALWAYS AS (...) STORED", "STORED 生成列"))


def rows(name, keep=None):
    out = []
    for c in S[name]["columns"]:
        if keep is not None and c["name"] not in keep:
            continue
        if "PK" in c["keys"]:
            k = "PK"
        elif "FK" in c["keys"]:
            k = "FK"
        elif "UK" in c["keys"] or "UQ*" in c["keys"]:
            k = "UK"
        elif c.get("auto_increment"):
            k = "GC"
        else:
            k = None
        out.append((k, c["name"], st(c["type"])))
    return out


def sub(name):
    return "%s　%s 字段" % (S[name]["cn"], len(S[name]["columns"]))


def stack(x, y, boxes, gap=36):
    """竖直堆放，返回下一个可用 y。"""
    for b in boxes:
        b.x, b.y = x, y
        y += b.h + gap
    return y - gap


def finish(d, pad=30, pad_top=0):
    """按所有可见元素（表框、分组框、连线的顶点、分组标题）自动确定画布尺寸。"""
    xs, ys = [], []
    for b in d.boxes:
        b.measure(d.t)
        xs += [b.x, b.x + b.w]
        ys += [b.y, b.y + b.h]
    for (x, y, w, h, cap) in d.frames:
        xs += [x, x + w]
        ys += [y - 26, y + h]          # 标题在框上方
    for c in d.conns:
        for (px, py) in c["points"]:
            xs.append(px)
            ys.append(py)
    d.w = int(max(xs)) + pad
    d.h = int(max(ys)) + pad
    return d


# =====================================================================
# 图 1：全局总览
# =====================================================================
def fig_overview():
    # 三列布局：画布宽度直接决定排版到 A4 版心后的字面尺寸，
    # 少一列（原 4 列）可把图内字号从约 5pt 提到约 6.8pt。
    d = Diagram(0, 0, fs=15.5, scale=3)
    W = 250
    X = (60, 380, 700)

    u = d.box("sys_user", X[0], 100, W, rows("sys_user", ["id", "username", "status"]),
              "sys_user", sub("sys_user"), C_AUTH)
    ur = d.box("sys_user_role", X[1], 100, W, rows("sys_user_role", ["id", "user_id", "role_id"]),
               "sys_user_role", sub("sys_user_role"), C_AUTH)
    r = d.box("sys_role", X[2], 100, W, rows("sys_role", ["id", "code", "name"]),
              "sys_role", sub("sys_role"), C_AUTH)
    up = d.box("sys_user_permission", X[1], 270, W,
               rows("sys_user_permission", ["id", "user_id", "perm_id"]),
               "sys_user_permission", "用户追加权限 6", C_AUTH)
    rp = d.box("sys_role_permission", X[2], 270, W, rows("sys_role_permission"),
               "sys_role_permission", "角色权限关联 4", C_AUTH)
    p = d.box("sys_permission", X[1], 440, W, rows("sys_permission", ["id", "code"]),
              "sys_permission", "权限字典 6", C_AUTH)

    lg = d.box("sys_login_log", X[0], 600, W, rows("sys_login_log", ["id", "user_id", "result"]),
               "sys_login_log", sub("sys_login_log"), C_LOG)
    ol = d.box("sys_operation_log", X[1], 600, W,
               rows("sys_operation_log", ["id", "user_id", "module"]),
               "sys_operation_log", sub("sys_operation_log"), C_LOG)

    sv = d.box("survey", X[0], 795, W, rows("survey", ["id", "title", "status", "create_by"]),
               "survey", sub("survey"), C_SUR)
    q = d.box("survey_question", X[1], 795, W,
              rows("survey_question", ["id", "survey_id", "question_type"]),
              "survey_question", sub("survey_question"), C_SUR)
    op = d.box("survey_question_option", X[2], 795, W,
               rows("survey_question_option", ["id", "question_id", "survey_id"]),
               "survey_question_option", "问卷选项 6", C_SUR)

    ash = d.box("answer_sheet", X[0], 1015, W,
                rows("answer_sheet", ["id", "survey_id", "user_id", "is_valid"]),
                "answer_sheet", sub("answer_sheet"), C_ANS)
    ai = d.box("answer_item", X[1], 1015, W,
               rows("answer_item", ["id", "sheet_id", "question_id", "option_values"]),
               "answer_item", sub("answer_item"), C_ANS)
    dr = d.box("survey_draft", X[2], 1015, W, rows("survey_draft", ["id", "survey_id", "user_id"]),
               "survey_draft", "填写草稿 6", C_ANS)

    # 四个分区框统一宽度，视觉上成一栏
    fw = X[2] + W + 18 - 42
    d.frame(42, 70, fw, 480, "① 认证与权限（RBAC）")
    d.frame(42, 570, fw, 165, "② 日志审计")
    d.frame(42, 765, fw, 190, "③ 问卷管理")
    d.frame(42, 985, fw, 190, "④ 答卷与统计")

    # ① RBAC
    d.conn([(310, u.row_y(0)), (380, u.row_y(0))], start="one", end="many")
    d.conn([(630, ur.row_y(2)), (665, ur.row_y(2)), (665, r.row_y(0)), (700, r.row_y(0))],
           start="many", end="one")
    d.conn([(825, r.y + r.h), (825, rp.y)], start="one", end="many")
    d.conn([(840, rp.y + rp.h), (840, p.row_y(0)), (630, p.row_y(0))], start="many", end="one")
    d.conn([(110, u.y + u.h), (110, up.row_y(0)), (380, up.row_y(0))],
           start="one", end="many", color=C_WEAK)
    d.conn([(505, up.y + up.h), (505, p.y)], start="many", end="one", color=C_WEAK)
    # ② 日志（虚线：日志保存 user_id 快照，用户删除后仍保留）
    d.conn([(150, u.y + u.h), (150, 600)], start="one", end="many", dashed=True, color=C_LOG)
    # ③ 问卷
    d.conn([(310, sv.row_y(0)), (380, sv.row_y(0))], start="one", end="many")
    d.conn([(630, q.row_y(2)), (700, q.row_y(2))], start="one", end="many")
    # ④ 答卷
    d.conn([(200, sv.y + sv.h), (200, 1015)], start="one", end="many")
    d.conn([(310, ash.row_y(0)), (380, ash.row_y(0))], start="one", end="many")
    d.conn([(250, sv.y + sv.h), (250, 975), (665, 975), (665, dr.row_y(1)), (700, dr.row_y(1))],
           start="one", end="many", dashed=True, color=C_WEAK)
    finish(d, pad=34, pad_top=0)
    return d.render(OUT / "er_overview.png")


# =====================================================================
# 图 2：认证与权限（6 表，全字段）
# =====================================================================
def fig_auth():
    d = Diagram(0, 0, fs=18.0, scale=3)
    u = d.box("sys_user", 40, 110, 355, rows("sys_user"), "sys_user", sub("sys_user"), C_AUTH)
    ur = d.box("sys_user_role", 465, 110, 290, rows("sys_user_role"), "sys_user_role", sub("sys_user_role"), C_AUTH)
    r = d.box("sys_role", 825, 110, 280, rows("sys_role"), "sys_role", sub("sys_role"), C_AUTH)
    up = d.box("sys_user_permission", 465, u.y + u.h + 110, 290, rows("sys_user_permission"),
               "sys_user_permission", sub("sys_user_permission"), C_AUTH)
    rp = d.box("sys_role_permission", 825, r.y + r.h + 80, 280, rows("sys_role_permission"),
               "sys_role_permission", sub("sys_role_permission"), C_AUTH)
    p = d.box("sys_permission", 825, rp.y + rp.h + 80, 280, rows("sys_permission"),
              "sys_permission", sub("sys_permission"), C_AUTH)

    d.conn([(395, u.row_y(0)), (430, u.row_y(0)), (430, ur.row_y(1)), (465, ur.row_y(1))],
           start="one", end="many", badge="1:N", badge_at=(430, (u.row_y(0) + ur.row_y(1)) / 2))
    d.conn([(755, ur.row_y(2)), (790, ur.row_y(2)), (790, r.row_y(0)), (825, r.row_y(0))],
           start="many", end="one")
    d.conn([(915, r.y + r.h), (915, rp.y)], start="one", end="many", badge="1:N",
           badge_at=(915, (r.y + r.h + rp.y) / 2))
    d.conn([(1005, rp.y + rp.h), (1005, p.y)], start="many", end="one", badge="N:1",
           badge_at=(1005, (rp.y + rp.h + p.y) / 2))
    d.conn([(120, u.y + u.h), (120, up.row_y(1)), (465, up.row_y(1))], start="one", end="many", color=C_WEAK)
    d.conn([(755, up.row_y(2)), (790, up.row_y(2)), (790, p.row_y(0)), (825, p.row_y(0))],
           start="many", end="one", color=C_WEAK)
    finish(d, pad=36)
    return d.render(OUT / "er_auth.png")


# =====================================================================
# 图 3：问卷与题目（3 表，全字段）
# =====================================================================
def fig_survey():
    d = Diagram(0, 0, fs=19.0, scale=3)
    sv = d.box("survey", 40, 110, 370, rows("survey"), "survey", sub("survey"), C_SUR)
    q = d.box("survey_question", 540, 110, 380, rows("survey_question"), "survey_question",
              sub("survey_question"), C_SUR)
    op = d.box("survey_question_option", 540, q.y + q.h + 110, 380, rows("survey_question_option"),
               "survey_question_option", sub("survey_question_option"), C_SUR)

    d.conn([(410, sv.row_y(0)), (475, sv.row_y(0)), (475, q.row_y(1)), (540, q.row_y(1))],
           start="one", end="many", badge="1:N", badge_at=(475, (sv.row_y(0) + q.row_y(1)) / 2))
    d.conn([(620, q.y + q.h), (620, op.y)], start="one", end="many", badge="1:N",
           badge_at=(620, (q.y + q.h + op.y) / 2))
    # 选项表冗余 survey_id：弱依赖（虚线），沿底部绕行
    base = op.y + op.h + 50
    d.conn([(90, sv.y + sv.h), (90, base), (495, base), (495, op.row_y(2))], start="one", end="none",
           dashed=True, color=C_WEAK)
    d.conn([(495, op.row_y(2)), (540, op.row_y(2))], start="none", end="many", dashed=True, color=C_WEAK)
    finish(d, pad=36)
    return d.render(OUT / "er_survey.png")


# =====================================================================
# 图 4：答卷 / 答案 / 草稿
# =====================================================================
def fig_answer():
    d = Diagram(0, 0, fs=18.0, scale=3)
    sv = d.box("survey", 40, 100, 300, rows("survey", ["id", "title", "status", "create_by", "deleted"]),
               "survey", sub("survey"), C_SUR)
    sh = d.box("answer_sheet", 420, 100, 330, rows("answer_sheet"), "answer_sheet", sub("answer_sheet"), C_ANS)
    ai = d.box("answer_item", 420, sh.y + sh.h + 100, 330, rows("answer_item"), "answer_item", sub("answer_item"),
               C_ANS)
    dr = d.box("survey_draft", 850, ai.y, 310, rows("survey_draft"), "survey_draft", sub("survey_draft"), C_ANS)

    d.conn([(340, sv.row_y(0)), (380, sv.row_y(0)), (380, sh.row_y(1)), (420, sh.row_y(1))],
           start="one", end="many", badge="1:N", badge_at=(380, (sv.row_y(0) + sh.row_y(1)) / 2))
    d.conn([(470, sh.y + sh.h), (470, ai.y)], start="one", end="many", badge="1:N",
           badge_at=(470, (sh.y + sh.h + ai.y) / 2))
    d.conn([(70, sv.y + sv.h), (70, ai.y + ai.h + 45), (810, ai.y + ai.h + 45), (810, dr.row_y(1)),
            (850, dr.row_y(1))], start="one", end="many", dashed=True, color=C_WEAK)
    d.conn([(750, sh.row_y(2)), (800, sh.row_y(2)), (800, dr.row_y(2)), (850, dr.row_y(2))],
           start="none", end="none", dashed=True, color=C_GRAY)
    finish(d, pad=36)
    return d.render(OUT / "er_answer.png")


if __name__ == "__main__":
    for f in (fig_overview, fig_auth, fig_survey, fig_answer):
        print(f())
