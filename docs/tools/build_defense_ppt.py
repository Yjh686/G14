#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成 D-10 答辩 PPT（docs/答辩PPT.pptx）。

设计约束：
- 16:9（13.333 x 7.5 英寸），投影仪通用比例。
- 配色沿用系统界面：深蓝 #1F2A44 作标题底、橙色 #E8963C 作强调（与统计图表主色一致）。
- 中文字体统一微软雅黑，避免在答辩机上回退成宋体。
- 每页只讲一件事，标题即结论（评委扫标题就能跟上）。
"""
import os
import sys

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

BASE = os.path.dirname(os.path.abspath(__file__))          # D:\G14(1)\backend\target
ROOT = os.path.abspath(os.path.join(BASE, "..", ".."))      # D:\G14(1)
SHOTS = os.path.join(ROOT, "docs", "答辩素材")
OUT = os.path.join(ROOT, "docs", "答辩PPT.pptx")

NAVY = RGBColor(0x1F, 0x2A, 0x44)
ORANGE = RGBColor(0xE8, 0x96, 0x3C)
GRAY = RGBColor(0x60, 0x62, 0x66)
LIGHT = RGBColor(0xF5, 0xF6, 0xF8)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
GREEN = RGBColor(0x2E, 0x9E, 0x5B)
FONT = "微软雅黑"

W = Inches(13.333)
H = Inches(7.5)


def blank(prs):
    return prs.slides.add_slide(prs.slide_layouts[6])


def rect(slide, x, y, w, h, fill=None, line=None):
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    if fill is None:
        shape.fill.background()
    else:
        shape.fill.solid()
        shape.fill.fore_color.rgb = fill
    if line is None:
        shape.line.fill.background()
    else:
        shape.line.color.rgb = line
        shape.line.width = Pt(1)
    shape.shadow.inherit = False
    return shape


def text(slide, x, y, w, h, lines, size=18, color=GRAY, bold=False,
         align=PP_ALIGN.LEFT, space_after=6, line_spacing=1.25, anchor=MSO_ANCHOR.TOP):
    """lines: str 或 [(文字, size, color, bold), ...]"""
    box = slide.shapes.add_textbox(x, y, w, h)
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0

    if isinstance(lines, str):
        lines = [(lines, size, color, bold)]

    for i, item in enumerate(lines):
        s, sz, cl, bd = (item if isinstance(item, tuple) else (item, size, color, bold))
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        p.line_spacing = line_spacing
        run = p.add_run()
        run.text = s
        run.font.size = Pt(sz)
        run.font.color.rgb = cl
        run.font.bold = bd
        run.font.name = FONT
    return box


def header(slide, kicker, title, sub=None):
    """页面标题区：小标 + 主标题（主标题即结论）+ 可选副标题。"""
    rect(slide, 0, 0, W, Inches(0.09), fill=ORANGE)
    text(slide, Inches(0.72), Inches(0.44), Inches(11.9), Inches(0.3),
         [(kicker, 12, ORANGE, True)])
    text(slide, Inches(0.72), Inches(0.76), Inches(11.9), Inches(0.62),
         [(title, 30, NAVY, True)])
    if sub:
        text(slide, Inches(0.72), Inches(1.44), Inches(11.9), Inches(0.4),
             [(sub, 14, GRAY, False)])


def footer(slide, no, note="校园问卷信息调查管理系统 · 期末答辩"):
    rect(slide, Inches(0.72), Inches(6.92), Inches(11.9), Emu(9525), fill=RGBColor(0xDC, 0xDF, 0xE6))
    text(slide, Inches(0.72), Inches(7.02), Inches(8), Inches(0.3),
         [(note, 10, RGBColor(0xA8, 0xAB, 0xB2), False)])
    text(slide, Inches(11.6), Inches(7.02), Inches(1), Inches(0.3),
         [(str(no), 10, RGBColor(0xA8, 0xAB, 0xB2), False)], align=PP_ALIGN.RIGHT)


def card(slide, x, y, w, h, title, body, accent=ORANGE, title_size=16, body_size=13):
    rect(slide, x, y, w, h, fill=LIGHT)
    rect(slide, x, y, Emu(38100), h, fill=accent)
    text(slide, x + Inches(0.24), y + Inches(0.16), w - Inches(0.4), Inches(0.3),
         [(title, title_size, NAVY, True)])
    text(slide, x + Inches(0.24), y + Inches(0.52), w - Inches(0.4), h - Inches(0.6),
         body, size=body_size, color=GRAY, line_spacing=1.3, space_after=4)


def kpi(slide, x, y, w, value, label, color=ORANGE, vsize=34):
    text(slide, x, y, w, Inches(0.6), [(value, vsize, color, True)])
    text(slide, x, y + Inches(0.62), w, Inches(0.3), [(label, 12, GRAY, False)])


def table(slide, x, y, w, rows, col_widths, head_size=12, body_size=11, row_h=Inches(0.4)):
    nrows, ncols = len(rows), len(rows[0])
    shape = slide.shapes.add_table(nrows, ncols, x, y, w, row_h * nrows)
    tbl = shape.table
    tbl.first_row = True
    total = sum(col_widths)
    for i, cw in enumerate(col_widths):
        tbl.columns[i].width = Emu(int(w * cw / total))
    for r in range(nrows):
        tbl.rows[r].height = row_h
        for c in range(ncols):
            cell = tbl.cell(r, c)
            cell.text = str(rows[r][c])
            cell.margin_left = Inches(0.08)
            cell.margin_right = Inches(0.08)
            cell.margin_top = Inches(0.02)
            cell.margin_bottom = Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            if r == 0:
                cell.fill.fore_color.rgb = NAVY
            else:
                cell.fill.fore_color.rgb = WHITE if r % 2 else LIGHT
            for p in cell.text_frame.paragraphs:
                p.alignment = PP_ALIGN.LEFT if c == 0 else PP_ALIGN.CENTER
                for run in p.runs:
                    run.font.name = FONT
                    run.font.size = Pt(head_size if r == 0 else body_size)
                    run.font.bold = (r == 0)
                    run.font.color.rgb = WHITE if r == 0 else GRAY
    return tbl


def shot(slide, name, x, y, w, h, caption=None):
    """放一张截图：等比缩放到 w x h 的框内并居中。"""
    path = os.path.join(SHOTS, name)
    if not os.path.exists(path):
        rect(slide, x, y, w, h, fill=LIGHT, line=RGBColor(0xC0, 0xC4, 0xCC))
        text(slide, x, y + h / 2, w, Inches(0.4), [("（缺图：" + name + "）", 12, GRAY, False)],
             align=PP_ALIGN.CENTER)
        return
    pic = slide.shapes.add_picture(path, x, y, w, h)   # python-pptx 会自动按比例缩放
    pic.line.color.rgb = RGBColor(0xD8, 0xDB, 0xE2)
    pic.line.width = Pt(0.75)
    if caption:
        text(slide, x, y + h + Inches(0.08), w, Inches(0.3),
             [(caption, 11, GRAY, False)], align=PP_ALIGN.CENTER)


def build():
    prs = Presentation()
    prs.slide_width = W
    prs.slide_height = H

    # ---------------------------------------------------------------- 01 封面
    s = blank(prs)
    rect(s, 0, 0, W, H, fill=NAVY)
    rect(s, 0, 0, Emu(91440 * 3), H, fill=ORANGE)
    text(s, Inches(1.1), Inches(1.9), Inches(11), Inches(0.4),
         [("SPRING BOOT 3 + VUE 3 · 前后端分离", 14, ORANGE, True)])
    text(s, Inches(1.1), Inches(2.4), Inches(11), Inches(1.6),
         [("校园问卷信息调查管理系统", 48, WHITE, True)], line_spacing=1.15)
    text(s, Inches(1.1), Inches(3.85), Inches(10.5), Inches(0.9),
         [("问卷创建 · 发布分发 · 在线填写 · 答卷回收 · 统计分析可视化闭环", 20, RGBColor(0xC8, 0xCE, 0xDB), False)])
    rect(s, Inches(1.1), Inches(4.85), Inches(1.4), Emu(28575), fill=ORANGE)
    text(s, Inches(1.1), Inches(5.15), Inches(11), Inches(1.2),
         [("三种角色：学生 / 教师 / 系统管理员", 15, RGBColor(0xA9, 0xB3, 0xC7), False),
          ("65 条功能需求 · 17 张表 · 72 个接口 · 26 个页面 · 954 条测试断言", 15, RGBColor(0xA9, 0xB3, 0xC7), False)],
         space_after=8)
    text(s, Inches(1.1), Inches(6.6), Inches(11), Inches(0.4),
         [("课程期末项目答辩 · 2026-11", 12, RGBColor(0x7C, 0x89, 0xA1), False)])

    # ---------------------------------------------------------------- 02 是什么
    s = blank(prs)
    header(s, "01 / 项目概览",
           "把「发问卷—填问卷—看结果」做成闭环",
           "教师不必再靠微信群接龙 + 手动数 Excel；学生不必再为了填一份问卷注册第四个账号。")
    kpi(s, Inches(0.72), Inches(2.16), Inches(2.7), "65", "功能需求（P0 52 / P1 13）")
    kpi(s, Inches(3.62), Inches(2.16), Inches(2.7), "17", "数据库表（5 个业务域）")
    kpi(s, Inches(6.52), Inches(2.16), Inches(2.7), "72", "RESTful 接口")
    kpi(s, Inches(9.42), Inches(2.16), Inches(2.7), "954", "自动化测试断言", color=GREEN, vsize=34)

    y = Inches(3.36)
    card(s, Inches(0.72), y, Inches(3.86), Inches(3.2),
         "教师",
         ["建卷出题（单/多选、填空、多行文本、评分五种题型）",
          "发布后结构锁定，避免收着答卷改题目",
          "实时统计、导出 Excel、共享为模板"],
         accent=RGBColor(0x3A, 0x7B, 0xD5))
    card(s, Inches(4.73), y, Inches(3.86), Inches(3.2),
         "学生",
         ["按分享码或大厅进入，免注册直接用校园账号",
          "草稿自动保存、断点续答、限填一次的幂等保证",
          "查看自己的填写记录、给模板打分"],
         accent=GREEN)
    card(s, Inches(8.74), y, Inches(3.88), Inches(3.2),
         "管理员",
         ["用户与角色管理、Excel 批量导入",
          "模板审核、全量问卷与答卷数据管理",
          "登录/操作日志审计、站点与密码策略配置"],
         accent=ORANGE)
    footer(s, 2)

    # ---------------------------------------------------------------- 03 权限模型
    s = blank(prs)
    header(s, "02 / 权限模型",
           "角色只有三个，「能不能发问卷」是权限不是角色",
           "50 个权限码按「模块:动作[:范围]」命名，菜单、路由、接口三处都由权限码驱动。")
    table(s, Inches(0.72), Inches(2.06), Inches(6.3), [
        ["角色", "权限码", "典型能力"],
        ["ADMIN", "50", "全部功能 + 用户/日志/配置/审核"],
        ["TEACHER", "23", "自己的问卷、统计、导出、共享模板"],
        ["STUDENT", "10", "填写、草稿、我的记录、模板评分"],
    ], [1.0, 0.8, 2.6])
    text(s, Inches(0.72), Inches(3.66), Inches(6.3), Inches(3.0), [
        ("为什么要「追加权限表」", 15, NAVY, True),
        ("学生干部要发问卷时，很多做法是新增一个「学生干部」角色——"
         "角色一多，权限矩阵就退化成一张需要背下来的对照表。", 13, GRAY, False),
        ("我们的做法：给这个学生单独追加 survey:create，生效权限 = 角色权限 ∪ 追加权限。"
         "角色永远只有三个，例外写在 sys_user_permission 里，可查、可撤、可审计。", 13, GRAY, False),
    ], space_after=8)

    card(s, Inches(7.32), Inches(2.06), Inches(5.3), Inches(1.42),
         "三层鉴权，缺一不可",
         ["方法级 @RequirePerm → 视角级 scope 校验 → 数据级 requireOwned"], accent=NAVY)
    card(s, Inches(7.32), Inches(3.66), Inches(5.3), Inches(1.42),
         "菜单不是写死的",
         ["侧边栏按 hasPerm 动态生成，学生登录根本看不到「我的问卷」"], accent=NAVY)
    card(s, Inches(7.32), Inches(5.26), Inches(5.3), Inches(1.42),
         "越权测试覆盖到 403",
         ["7 个冒烟脚本里每一条「别人不能做」都断言了状态码，不是靠人点"], accent=GREEN)
    footer(s, 3)

    # ---------------------------------------------------------------- 04 数据模型
    s = blank(prs)
    header(s, "03 / 数据模型",
           "17 张表，五张子表用 JSON，其余全部拆表",
           "判断标准只有一条：这个子元素将来会不会被单独查询、聚合、排序。")
    table(s, Inches(0.72), Inches(2.06), Inches(7.9), [
        ["业务域", "表", "关键设计"],
        ["认证权限", "sys_user 等 8 张", "token_version 让旧令牌立刻失效"],
        ["问卷", "survey + 2 张子表", "发布后结构锁定，分享码可空唯一"],
        ["答题", "answer_sheet / item / draft", "生成列 + 唯一索引保证限填一次"],
        ["模板", "survey_template / rating", "结构存 JSON 快照，选项存文本"],
        ["配置", "sys_config", "键值表 + is_public 区分公开项"],
    ], [0.9, 2.0, 3.2], row_h=Inches(0.42))

    text(s, Inches(0.72), Inches(4.86), Inches(7.9), Inches(1.9), [
        ("拆表 vs 存 JSON 的实际取舍", 15, NAVY, True),
        ("answer_item 的选项必须拆成可查询的列，因为统计要按选项 GROUP BY、要算频次；"
         "而模板结构只在「打开模板详情」时整块读取，从不按某一道题跨模板查询——"
         "给它建两张子表只会多两次 JOIN，换不来任何查询能力。", 12.5, GRAY, False),
    ], space_after=6)

    card(s, Inches(8.92), Inches(2.06), Inches(3.7), Inches(2.0),
         "不用数据库外键",
         ["删除顺序会变成雷区：清理测试数据时外键会拦住子表删除。"
          "代价是可能出现孤儿行，所以清理脚本严格按子表→父表顺序删。"], accent=ORANGE, body_size=12)
    card(s, Inches(8.92), Inches(4.24), Inches(3.7), Inches(2.44),
         "逻辑删除不一致的坑",
         ["survey 的 status 不含 DELETED —— 删除只由 deleted=1 表达。"
          "两处都存状态，就一定会出现「status=CLOSED 但 deleted=1」这种自相矛盾的行。"],
         accent=ORANGE, body_size=12)
    footer(s, 4)

    # ---------------------------------------------------------------- 05 决策：限填一次
    s = blank(prs)
    header(s, "04 / 核心设计 · 限填一次",
           "把「只能填一次」交给数据库，而不是交给一段 if",
           "应用层「先查有没有交过、没有再插」在并发下会同时通过检查——压测里实测出了 500。")
    rect(s, Inches(0.72), Inches(2.1), Inches(7.5), Inches(1.62), fill=RGBColor(0x2B, 0x2F, 0x3A))
    text(s, Inches(0.95), Inches(2.26), Inches(7.1), Inches(1.4), [
        ("generated always as (", 12.5, RGBColor(0x9C, 0xD3, 0x8E), False),
        ("  case when limit_once = 1 and deleted = 0", 12.5, RGBColor(0xE8, 0xC1, 0x7A), False),
        ("  then user_id else null end) stored", 12.5, RGBColor(0xE8, 0xC1, 0x7A), False),
        ("unique key uk_survey_user (survey_id, uniq_user)", 12.5, RGBColor(0x9C, 0xD3, 0x8E), False),
    ], space_after=2, line_spacing=1.2)

    text(s, Inches(0.72), Inches(3.96), Inches(7.5), Inches(2.9), [
        ("为什么不能直接给 (survey_id, user_id) 建唯一索引", 15, NAVY, True),
        ("那样「允许重复填写」的问卷就彻底不可用了。", 13, GRAY, False),
        ("为什么不用「先查再插」", 15, NAVY, True),
        ("100 并发下多个请求同时查到「没交过」，然后一起去插 —— 第二个撞唯一键抛 500。"
         "第一版就是这么写的，压测 10 个 500 全部来自这里。", 13, GRAY, False),
    ], space_after=6)

    card(s, Inches(8.52), Inches(2.1), Inches(4.1), Inches(2.2),
         "匿名 ≠ 不存 user_id",
         ["匿名问卷依然要记 user_id，否则「限填一次」和「我的记录」都无法实现。"
          "匿名只发生在出口：DTO 组装时把这几个字段裁掉，只有一处裁剪点。"],
         accent=NAVY, body_size=12)
    card(s, Inches(8.52), Inches(4.48), Inches(4.1), Inches(2.2),
         "草稿保存同理",
         ["survey_draft 用 INSERT ... ON DUPLICATE KEY UPDATE。"
          "「先 select 再 update」在 REPEATABLE READ 下修不干净："
          "本事务快照早于对方提交，重查依然看不见那一行。"],
         accent=NAVY, body_size=12)
    footer(s, 5)

    # ---------------------------------------------------------------- 06 决策：发布锁结构
    s = blank(prs)
    header(s, "05 / 核心设计 · 发布即冻结",
           "发布之后，题目结构锁定，只能改标题和时间",
           "收着答卷改题目，会让已经交上来的人答案对不上题——这类数据损坏无法事后修复。")
    rows = [["状态", "能做什么"],
            ["DRAFT 草稿", "自由增删改题目、调整顺序、预览"],
            ["PUBLISHED 收集中", "只能改标题/简介/开始截止时间；要改题目请「复制为新问卷」"],
            ["PAUSED 已暂停", "暂时不接受新答卷，已有答卷仍可查看统计"],
            ["CLOSED 已关闭", "统计与导出保留，不再接受答卷"]]
    table(s, Inches(0.72), Inches(2.06), Inches(7.4), rows, [1.0, 3.4], row_h=Inches(0.52))

    text(s, Inches(0.72), Inches(4.5), Inches(7.4), Inches(2.3), [
        ("为什么不给「编辑已发布问卷」开个口子", 15, NAVY, True),
        ("看起来只是少一个按钮，实际是省掉了整条一致性链路：不需要做答卷版本号、"
         "不需要处理「第 3 题删了，已提交的 200 份答卷里这道题怎么办」、"
         "不需要在统计时判断「这道题是哪个版本的」。", 12.5, GRAY, False),
        ("想改结构？复制为新问卷 —— 旧答卷留在旧问卷里，统计口径永远干净。", 12.5, ORANGE, True),
    ], space_after=6)

    card(s, Inches(8.42), Inches(2.06), Inches(4.2), Inches(2.3),
         "fillable 由后端算",
         ["status==PUBLISHED 且 now 在 [startTime, endTime) 区间内。"
          "不依赖定时任务改状态——定时任务挂一次，问卷就永远开着或永远关着。"],
         accent=GREEN, body_size=12)
    card(s, Inches(8.42), Inches(4.56), Inches(4.2), Inches(2.24),
         "分享码 8 位",
         ["去掉了 0/O/1/I 这类易混字符，字符集 "
          "ABCDEFGHJKLMNPQRSTUVWXYZ23456789。"
          "学生念出来、抄在黑板上的场景，比扫码更常见。"],
         accent=GREEN, body_size=12)
    footer(s, 6)

    # ---------------------------------------------------------------- 07 统计口径
    s = blank(prs)
    header(s, "06 / 统计口径",
           "多选题的占比加起来超过 100%，这是对的",
           "分母是「该题有效作答人数」，不是「所有选项被选次数之和」。")
    table(s, Inches(0.72), Inches(2.02), Inches(6.6), [
        ["选项", "人数", "占比"],
        ["自学", "4", "80%"],
        ["小组讨论", "3", "60%"],
        ["课堂讲授", "2", "40%"],
        ["实践作业", "2", "40%"],
    ], [2.0, 1.0, 1.0], row_h=Inches(0.4))
    text(s, Inches(0.72), Inches(4.28), Inches(6.6), Inches(2.4), [
        ("5 个人作答，4 个人选了「自学」→ 80%。", 13, GRAY, False),
        ("如果换成「占选项总次数」做分母，4/11 = 36%，看起来「正常」了，"
         "但它回答的是另一个问题——「这个选项在所有勾选里占多少」，"
         "而老师想知道的是「有多大比例的学生需要自学」。", 12.5, GRAY, False),
        ("所以前端对多选题不画饼图（画出来会超过一整圈），并在图下写明原因。",
         12.5, ORANGE, True),
    ], space_after=8)

    card(s, Inches(7.62), Inches(2.02), Inches(5.0), Inches(1.6),
         "只统计有效答卷",
         ["被标记无效的答卷不进任何数字，包括分母。统计页与导出的口径完全一致。"],
         accent=NAVY, body_size=12)
    card(s, Inches(7.62), Inches(3.78), Inches(5.0), Inches(1.6),
         "没有数据就说没有",
         ["0 份有效答卷时返回 0 与空数组，前端显示「暂无数据」占位——"
          "不出现 NaN、不抛异常、也不显示一个「0.0 分」让人误解。"],
         accent=NAVY, body_size=12)
    card(s, Inches(7.62), Inches(5.54), Inches(5.0), Inches(1.26),
         "不预聚合",
         ["预聚合表会引入「答卷提交了但统计没变」的窗口。现算一次 < 100ms。"],
         accent=ORANGE, body_size=12)
    footer(s, 7)

    # ---------------------------------------------------------------- 08 性能总览
    s = blank(prs)
    header(s, "07 / 性能验收",
           "提交接口 P95 674ms，指标要求 3000ms",
           "100 并发、ramp-up 10 秒、持续 5 分钟，混合比例 查询 20% / 草稿 10% / 提交 70%。")
    kpi(s, Inches(0.72), Inches(2.04), Inches(2.6), "204.4", "req/s 吞吐")
    kpi(s, Inches(3.5), Inches(2.04), Inches(2.6), "539.8", "ms 平均（要求 <1500）", color=GREEN)
    kpi(s, Inches(6.28), Inches(2.04), Inches(2.6), "673.9", "ms P95（要求 <3000）", color=GREEN)
    kpi(s, Inches(9.06), Inches(2.04), Inches(2.6), "0.00%", "故障率（要求 <0.1%）", color=GREEN)

    table(s, Inches(0.72), Inches(3.32), Inches(11.9), [
        ["阶段", "吞吐", "avg", "P95", "故障率", "关键改动"],
        ["首轮基线", "19.8", "3458ms", "5305ms", "20.18%", "单账号 + 一个长事务包住全部"],
        ["拆成两个事务", "16.3", "5710ms", "9569ms", "5.14%", "REQUIRES_NEW 反而更慢"],
        ["改用 100 个独立账号", "39.8", "2717ms", "3687ms", "0.00%", "修压测模型缺陷"],
        ["3 次 commit 合并为 1 次", "58.2", "1789ms", "1987ms", "0.00%", "P95 首次达标"],
        ["关闭 SQL 日志", "211.0", "504ms", "655ms", "0.00%", "去掉 System.out 串行化"],
        ["死锁结构性消除", "204.4", "540ms", "674ms", "0.00%", "按主键删草稿"],
    ], [1.9, 0.7, 0.85, 0.85, 0.8, 3.2], row_h=Inches(0.4))

    text(s, Inches(0.72), Inches(6.28), Inches(11.9), Inches(0.6),
         [("六条通过条件全部满足：P95、平均、故障率、答卷数=成功提交数、无 5xx、MySQL 无新增死锁日志。", 13, NAVY, True)])
    footer(s, 8)

    # ---------------------------------------------------------------- 09 性能：两个真凶
    s = blank(prs)
    header(s, "08 / 性能诊断",
           "瓶颈不在代码里：一次 fsync，和一行日志配置",
           "指标跑不过的时候，先证明瓶颈在哪，再改代码。")
    card(s, Inches(0.72), Inches(2.06), Inches(5.8), Inches(2.5),
         "① 每次 commit 都要等一次磁盘落盘",
         ["数据库层基准测试：热点行 UPDATE + commit 吞吐锁死在约 170 次/秒，"
          "线程从 1 加到 32，吞吐不变、延迟从 6.5ms 线性涨到 183ms。",
          "一次提交原本要付 3 次 commit（答卷入库 / 更新回收计数 / 写操作日志），"
          "理论上限 170÷3 ≈ 57 次/秒 —— 与实测吻合。"],
         accent=ORANGE, body_size=12.5)
    card(s, Inches(0.72), Inches(4.74), Inches(5.8), Inches(2.06),
         "没有偷偷改 MySQL 参数",
         ["把 innodb_flush_log_at_trx_commit 调成 2、sync_binlog 调成 0，"
          "吞吐直接涨到 2600 次/秒。",
          "但那是拿持久性换性能：崩溃可能丢最近 1 秒的事务。"
          "做了一次对照实验确认收益量级，随即还原，没有采纳。"],
         accent=GREEN, body_size=12.5)

    card(s, Inches(6.82), Inches(2.06), Inches(5.8), Inches(2.5),
         "② 每请求往控制台同步写十几行 SQL",
         ["mybatis-plus 配了 StdOutImpl + DEBUG 级别：单条请求产生约 9KB 日志，"
          "7655 个请求写完 68MB。",
          "System.out 是同步无缓冲的，写锁本身成了全局串行点——"
          "关掉之后吞吐从 58 涨到 211，涨了 3.6 倍。"],
         accent=ORANGE, body_size=12.5)
    card(s, Inches(6.82), Inches(4.74), Inches(5.8), Inches(2.06),
         "这也解释了为什么「小压力下一切正常」",
         ["10 并发冒烟时 P95 只有 196ms，看起来毫无问题。"
          "瓶颈只有在排队时才会显形：单请求本身不慢，慢的是它们挤在了一起。"],
         accent=NAVY, body_size=12.5)
    footer(s, 9)

    # ---------------------------------------------------------------- 10 死锁
    s = blank(prs)
    header(s, "09 / 并发缺陷",
           "死锁先治标、再治本：指标达标不等于问题解决",
           "重试能让 500 消失，但 MySQL 的死锁日志还在往前推进。")
    rect(s, Inches(0.72), Inches(2.06), Inches(5.4), Inches(2.42), fill=LIGHT)
    rect(s, Inches(0.72), Inches(2.06), Emu(38100), Inches(2.42), fill=RGBColor(0xC0, 0x39, 0x2B))
    text(s, Inches(0.96), Inches(2.2), Inches(5.0), Inches(0.32), [("治标：语句级重试", 16, NAVY, True)])
    text(s, Inches(0.96), Inches(2.6), Inches(5.0), Inches(1.8), [
        ("catch ConcurrencyFailureException，退避重试 3 次。", 12.5, GRAY, False),
        ("动手前必须先去掉 saveDraft 的 @Transactional —— 事务包着的时候，锁冲突回滚的是整个事务，语句级重试必然继续失败。", 12.5, GRAY, False),
        ("结果：故障率 0%，六条指标全过。", 12.5, GRAY, False),
    ], space_after=6)

    rect(s, Inches(6.42), Inches(2.06), Inches(6.2), Inches(2.42), fill=LIGHT)
    rect(s, Inches(6.42), Inches(2.06), Emu(38100), Inches(2.42), fill=GREEN)
    text(s, Inches(6.66), Inches(2.2), Inches(5.8), Inches(0.32), [("治本：改掉加锁顺序", 16, NAVY, True)])
    text(s, Inches(6.66), Inches(2.6), Inches(5.8), Inches(1.8), [
        ("死锁双方锁的其实不是同一行：DELETE 在唯一索引上取了间隙锁，并发的 upsert 想在同一段间隙里插记录，插入意向锁与之互斥。", 12.5, GRAY, False),
        ("改法：把「提交后顺手删草稿」移出提交事务，改成按主键 deleteById —— 只锁那一条记录，完全不碰间隙。", 12.5, GRAY, False),
    ], space_after=6)

    rect(s, Inches(0.72), Inches(4.76), Inches(11.9), Inches(2.02), fill=NAVY)
    text(s, Inches(1.0), Inches(4.96), Inches(11.3), Inches(1.7), [
        ("验收条件写的是「MySQL 无死锁日志」，不是「无 500」——这两个条件的区别就是这个项目的教训。", 15, WHITE, True),
        ("把并发缺陷交给重试去兜，用户看不到报错了，但问题只是从「用户可见」变成了「日志可见」。"
         "如果验收只看 500 的数量，这一类问题会带着绿灯通过。", 13, RGBColor(0xC8, 0xCE, 0xDB), False),
    ], space_after=10)

    # ---------------------------------------------------------------- 11 测试体系
    s = blank(prs)
    header(s, "10 / 测试",
           "954 条断言，全部可一键重跑",
           "不是「点了一遍没问题」，而是每次改完都能在几分钟内知道有没有退步。")
    table(s, Inches(0.72), Inches(2.0), Inches(7.3), [
        ["层次", "规模", "回答什么问题"],
        ["单元测试", "34 例", "日期、字典、图表 option 这类纯函数算得对不对"],
        ["接口冒烟", "710 条 / 7 个脚本", "每一条权限与业务规则是否成立"],
        ["前端 E2E", "200 条 / 6 个脚本", "菜单、跳转、提示文案在页面上真的对吗"],
        ["压力测试", "6 条判定", "100 并发涌进来会不会垮"],
        ["长稳测试", "4 条判定", "挂一整天会不会慢慢坏掉"],
    ], [1.05, 1.5, 3.2], row_h=Inches(0.46))

    text(s, Inches(0.72), Inches(5.02), Inches(7.3), Inches(1.7), [
        ("为什么冒烟和 E2E 都要写", 14, NAVY, True),
        ("冒烟 30 秒跑完，改后端时反馈最快；但它验不了「菜单里到底有没有这一项」。"
         "E2E 慢但抓到过「接口全绿、页面全崩」——日志接口忘了包统一响应结构，"
         "前端拦截器把裸 JSON 原样透传，接口测试全过，页面必然崩。", 12, GRAY, False),
    ], space_after=6)

    card(s, Inches(8.32), Inches(2.0), Inches(4.3), Inches(2.2),
         "测试找出 14 个真缺陷",
         ["包括：并发首存 500、100 并发雪崩、零答卷导出缺 Sheet、"
          "导入时空值 NPE、审核队列显示原始枚举、评分能点半颗星。",
          "每一个都记在测试文档的缺陷表里：根因 + 修法。"],
         accent=ORANGE, body_size=12)
    card(s, Inches(8.32), Inches(4.38), Inches(4.3), Inches(2.34),
         "也写清了没测到什么",
         ["只验了 Chrome、移动端未适配、100 并发以上未验证、"
          "Docker 只做了配置静态校验没有真正 build 起服务。",
          "把边界写进文档，比答辩时被问出来好。"],
         accent=GREEN, body_size=12)
    footer(s, 11)

    # ---------------------------------------------------------------- 12 截图：教师
    s = blank(prs)
    header(s, "11 / 系统实况 · 教师",
           "从建卷到拿到统计结果，全程不离开浏览器")
    shot(s, "03-问卷编辑器.png", Inches(0.72), Inches(1.86), Inches(7.55), Inches(4.72))
    text(s, Inches(8.62), Inches(2.0), Inches(4.0), Inches(4.6), [
        ("编辑器", 16, NAVY, True),
        ("左侧是题型面板，右侧是可拖动的题目列表。", 12.5, GRAY, False),
        ("必答、字数上限、评分上限按题型出现，不会给单选题显示「字数上限」。", 12.5, GRAY, False),
        ("整卷一次提交，后端做差量：有 id 的更新、没 id 的新增、库里多出来的删掉。", 12.5, GRAY, False),
    ], space_after=12)
    footer(s, 12)

    # ---------------------------------------------------------------- 13 截图：统计
    s = blank(prs)
    header(s, "12 / 系统实况 · 统计",
           "单选看分布，多选看覆盖，评分看均值，文本看高频词")
    shot(s, "04-统计分析-单选.png", Inches(0.72), Inches(1.84), Inches(6.05), Inches(3.78))
    shot(s, "05-统计分析-多选.png", Inches(6.9), Inches(1.84), Inches(6.05), Inches(3.78))
    text(s, Inches(0.72), Inches(5.78), Inches(12.2), Inches(1.0), [
        ("统计页不出现在教师菜单的二级项里，而是从「我的问卷 → 统计分析」进入——它是某一份问卷的视图，不是一个独立的栏目。", 12.5, GRAY, False),
        ("左图：单选给柱状图 + 饼图两套视角；右图：多选只给柱状图，并在图下注明「占比之和会超过 100%，这是正常语义」。", 12.5, GRAY, False),
    ], space_after=4)
    footer(s, 13)

    # ---------------------------------------------------------------- 14 截图：管理端
    s = blank(prs)
    header(s, "13 / 系统实况 · 管理端",
           "审核、审计、配置三件事，都不需要停机")
    shot(s, "08-模板中心.png", Inches(0.72), Inches(1.86), Inches(5.9), Inches(3.69))
    shot(s, "10-日志查询.png", Inches(6.76), Inches(1.86), Inches(5.9), Inches(3.69))
    text(s, Inches(0.72), Inches(5.72), Inches(12.2), Inches(1.1), [
        ("左：模板中心。教师共享的问卷变成一份「快照」，管理员审核通过后才对学生可见；选项存的是文本而不是选项 ID，来源问卷之后怎么改都不影响模板。", 12.5, GRAY, False),
        ("右：日志查询。登录日志与操作日志只读不可删——不是功能没做完，是审计日志本来就不该给删除入口。", 12.5, GRAY, False),
    ], space_after=4)
    footer(s, 14)

    # ---------------------------------------------------------------- 15 不足与边界
    s = blank(prs)
    header(s, "14 / 边界与不足",
           "说清楚没做到什么，比把话说满更可信")
    items = [
        ("Docker 编排没有真正跑起来",
         "compose 与 Dockerfile 已写好并通过静态校验，但本机 Docker daemon 起不来，"
         "所以「从零一条命令启动」这条还没有实证。文档里如实标注了。", ORANGE),
        ("压测只覆盖单机",
         "100 并发、单台 16 核机器、MySQL 与后端同机。真实多实例部署下的连接池、会话亲和性都未验证。", ORANGE),
        ("长稳测试是 15 分钟 + 24 小时采样中",
         "24 小时那一轮在代码冻结后跑，判定标准写了「每个时段故障率都 < 0.1%」，"
         "防止「前 23 小时好的、最后 1 小时崩」被平均值掩盖。", ORANGE),
        ("未做：移动端适配、问卷二维码、图表导出 PNG",
         "都在功能清单的 P2 里明确标了「不做」，理由是它们不影响 P0 闭环，"
         "而课程周期内把一条链路做扎实比铺开更值。", GRAY),
    ]
    y = Inches(1.94)
    for title, body, color in items:
        rect(s, Inches(0.72), y, Inches(11.9), Inches(1.12), fill=LIGHT)
        rect(s, Inches(0.72), y, Emu(38100), Inches(1.12), fill=color)
        text(s, Inches(0.98), y + Inches(0.13), Inches(11.4), Inches(0.34), [(title, 15, NAVY, True)])
        text(s, Inches(0.98), y + Inches(0.5), Inches(11.4), Inches(0.6), [(body, 12, GRAY, False)])
        y += Inches(1.24)
    footer(s, 15)

    # ---------------------------------------------------------------- 16 收尾
    s = blank(prs)
    rect(s, 0, 0, W, H, fill=NAVY)
    rect(s, 0, 0, Emu(91440 * 3), H, fill=ORANGE)
    text(s, Inches(1.1), Inches(2.3), Inches(11), Inches(0.4), [("Q & A", 15, ORANGE, True)])
    text(s, Inches(1.1), Inches(2.75), Inches(11), Inches(1.0), [("谢谢，请提问", 44, WHITE, True)])
    text(s, Inches(1.1), Inches(4.0), Inches(10.6), Inches(2.0), [
        ("三条硬指标都有可复现的证据：", 15, RGBColor(0xC8, 0xCE, 0xDB), False),
        ("· 100 并发提交 P95 674ms / 平均 540ms，六条通过条件逐条取证", 14, RGBColor(0xA9, 0xB3, 0xC7), False),
        ("· 长稳测试按每小时切分故障率，防止平均值掩盖尾部劣化", 14, RGBColor(0xA9, 0xB3, 0xC7), False),
        ("· 954 条自动化断言，改完代码几分钟内知道有没有退步", 14, RGBColor(0xA9, 0xB3, 0xC7), False),
    ], space_after=10)

    prs.save(OUT)
    return OUT


if __name__ == "__main__":
    out = build()
    print("saved:", out, os.path.getsize(out), "bytes")
