# -*- coding: utf-8 -*-
"""生成《数据库设计说明书：ER 图与数据字典》Word 文档。

数据源：schema.json（由 parse_schema.py 从 deploy/sql/*.sql 解析）
图片源：assets/er_*.png（由 build_er.py 渲染）
"""
import json
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Emu, Pt, RGBColor

BASE = Path(r"D:\G14(1)\docs\design\erd")
OUT = Path(r"C:\Users\25347\Desktop\数据库ER图与数据字典.docx")
S = json.loads((BASE / "schema.json").read_text(encoding="utf-8"))

CN_FONT = "宋体"
CN_HEI = "黑体"
MONO = "Consolas"

C_TXT = RGBColor(0x1F, 0x2A, 0x33)
C_GRAY = RGBColor(0x5A, 0x67, 0x73)
C_LINK = RGBColor(0x1F, 0x4E, 0x79)
HEAD_FILL = "1F4E79"
SUB_FILL = "DCE6F1"
ZEBRA = "F5F8FA"

MODULES = ["认证与权限", "日志审计", "问卷管理", "答卷与统计"]
MODULE_DESC = {
    "认证与权限": "RBAC 三元组（用户、角色、权限）及其两张关联表，另加一张用户级追加权限表用于给个别学生临时授予发布权限。",
    "日志审计": "登录日志与操作日志，只追加不修改，用于安全审计与问题追溯。",
    "问卷管理": "问卷主表、题目表、选项表，覆盖创建、编辑、发布、暂停、结束的全过程。",
    "答卷与统计": "答卷头、答案明细、填写草稿，支撑在线填写、断点续答、统计聚合与导出。",
}

# schema.json 是「表名 -> 元数据」的扁平字典，按模块 + 建表顺序重新排序
_ORDER = ["sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
          "sys_user_permission", "sys_login_log", "sys_operation_log",
          "survey", "survey_question", "survey_question_option",
          "answer_sheet", "answer_item", "survey_draft"]
assert set(_ORDER) == set(S.keys()), set(S.keys()) ^ set(_ORDER)
TABLES = [t for m in MODULES for t in _ORDER if S[t]["module"] == m]


# ---------------------------------------------------------------- 基础工具
def set_run_font(run, name=CN_FONT, size=10.5, bold=None, color=None, italic=None):
    run.font.name = name
    run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if italic is not None:
        run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color
    rpr = run._element.get_or_add_rPr()
    rf = rpr.find(qn("w:rFonts"))
    if rf is None:
        rf = OxmlElement("w:rFonts")
        rpr.append(rf)
    rf.set(qn("w:ascii"), name)
    rf.set(qn("w:hAnsi"), name)
    rf.set(qn("w:eastAsia"), CN_FONT if name != MONO else CN_FONT)
    return run


def style_font(style, name, size, bold=None, color=None):
    style.font.name = name
    style.font.size = Pt(size)
    if bold is not None:
        style.font.bold = bold
    if color is not None:
        style.font.color.rgb = color
    rpr = style.element.get_or_add_rPr()
    rf = rpr.find(qn("w:rFonts"))
    if rf is None:
        rf = OxmlElement("w:rFonts")
        rpr.append(rf)
    for a in ("w:ascii", "w:hAnsi", "w:eastAsia"):
        rf.set(qn(a), name)


def para(container, text="", size=10.5, bold=False, color=C_TXT, align=None,
         space_before=0, space_after=6, indent_first=None, line=1.55, font=CN_FONT):
    p = container.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(space_before)
    pf.space_after = Pt(space_after)
    pf.line_spacing = line
    if indent_first is not None:
        pf.first_line_indent = Pt(indent_first)
    if align is not None:
        p.alignment = align
    if text:
        set_run_font(p.add_run(text), font, size, bold, color)
    return p


def body(container, text, indent=True):
    """正文段落：中文首行缩进两字符。"""
    return para(container, text, size=10.5, align=WD_ALIGN_PARAGRAPH.JUSTIFY,
                indent_first=21 if indent else None)


def bullet(container, text, level=0):
    p = container.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Pt(21 + level * 21)
    pf.first_line_indent = Pt(-10.5)
    pf.space_after = Pt(3)
    pf.line_spacing = 1.5
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    set_run_font(p.add_run("· " if level == 0 else "– "), CN_FONT, 10.5, color=C_GRAY)
    set_run_font(p.add_run(text), CN_FONT, 10.5, color=C_TXT)
    return p


def heading(doc, text, level):
    h = doc.add_heading(level=level)
    apply_break(h)
    run = h.add_run(text)
    if level == 1:
        set_run_font(run, CN_HEI, 16, True, RGBColor(0x1F, 0x3B, 0x57))
    elif level == 2:
        set_run_font(run, CN_HEI, 13.5, True, RGBColor(0x24, 0x4A, 0x6B))
    else:
        set_run_font(run, CN_HEI, 12, True, RGBColor(0x2E, 0x5A, 0x7D))
    h.paragraph_format.space_before = Pt(14 if level == 1 else 10)
    h.paragraph_format.space_after = Pt(8 if level == 1 else 6)
    return h


def shade(cell, fill):
    tcpr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    tcpr.append(shd)


def cell_text(cell, text, size=9, bold=False, color=C_TXT, align=WD_ALIGN_PARAGRAPH.LEFT, font=CN_FONT):
    cell.text = ""
    p = cell.paragraphs[0]
    p.alignment = align
    pf = p.paragraph_format
    pf.space_before = Pt(1.5)
    pf.space_after = Pt(1.5)
    pf.line_spacing = 1.18
    if text:
        set_run_font(p.add_run(text), font, size, bold, color)


def set_col_widths(table, widths_cm):
    """按正文可用宽度等比缩放列宽，避免表格横向溢出页边距。"""
    total = sum(widths_cm)
    if total <= 0:
        return
    k = USABLE_CM / total
    widths_cm = [w * k for w in widths_cm]
    table.autofit = False
    tblpr = table._tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblpr.append(layout)
    for row in table.rows:
        for i, w in enumerate(widths_cm):
            row.cells[i].width = Cm(w)
    for i, w in enumerate(widths_cm):
        table.columns[i].width = Cm(w)


def repeat_header(row):
    trpr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:tblHeader")
    el.set(qn("w:val"), "true")
    trpr.append(el)


def cant_split(row):
    """禁止表格行跨页断开，避免页首出现只剩半行内容的碎行。"""
    trpr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:cantSplit")
    el.set(qn("w:val"), "true")
    trpr.append(el)


def keep_together(table):
    """让表格尽量不跨页（等价于 Word 的「允许跨页断行」取消 + 行间同页）。"""
    rows = table.rows
    for row in rows[:-1]:
        for cell in row.cells:
            for p in cell.paragraphs:
                p.paragraph_format.keep_with_next = True


ZW = "\u200b"


def brk(s):
    """在标识符的分隔符后插入零宽空格，给 Word 提供断行点。"""
    s = str(s)
    out = []
    for ch in s:
        out.append(ch)
        if ch in "._(,":
            out.append(ZW)
    return "".join(out)


def brk_col(rows, col):
    for r in rows:
        r[col] = brk(r[col])
    return rows


def keep_with_caption(table):
    """把表注黏在表尾：最后一行与后续段落保持同页。"""
    for cell in table.rows[-1].cells:
        for p in cell.paragraphs:
            p.paragraph_format.keep_with_next = True


def make_table(doc, header, rows, widths, aligns=None, zebra=True, font_size=9):
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = t.rows[0]
    repeat_header(hdr)
    cant_split(hdr)
    cant_split_all = True
    for i, h in enumerate(header):
        c = hdr.cells[i]
        cell_text(c, h, font_size, True, RGBColor(0xFF, 0xFF, 0xFF), WD_ALIGN_PARAGRAPH.CENTER, CN_HEI)
        shade(c, HEAD_FILL)
    for cell in hdr.cells:
        for cp in cell.paragraphs:
            cp.paragraph_format.keep_with_next = True
    for ri, r in enumerate(rows):
        newrow = t.add_row()
        cells = newrow.cells
        if cant_split_all:
            cant_split(newrow)
        for ci, v in enumerate(r):
            al = (aligns[ci] if aligns else WD_ALIGN_PARAGRAPH.LEFT)
            cell_text(cells[ci], str(v), font_size, False, C_TXT, al,
                      MONO if (aligns and aligns[ci] == WD_ALIGN_PARAGRAPH.LEFT and ci == 1 and font_size <= 9) else CN_FONT)
            if zebra and ri % 2 == 1:
                shade(cells[ci], ZEBRA)
    set_col_widths(t, widths)
    if len(rows) <= 3:
        keep_together(t)
    return t


def caption(doc, text, before=4, after=10):
    return para(doc, text, size=9.5, bold=True, color=C_GRAY,
                align=WD_ALIGN_PARAGRAPH.CENTER, space_before=before, space_after=after)


def field(paragraph, instr, placeholder=""):
    r = paragraph.add_run()
    b = OxmlElement("w:fldChar"); b.set(qn("w:fldCharType"), "begin")
    it = OxmlElement("w:instrText"); it.set(qn("xml:space"), "preserve"); it.text = instr
    sep = OxmlElement("w:fldChar"); sep.set(qn("w:fldCharType"), "separate")
    t = OxmlElement("w:t"); t.text = placeholder
    e = OxmlElement("w:fldChar"); e.set(qn("w:fldCharType"), "end")
    for el in (b, it, sep, t, e):
        r._r.append(el)
    set_run_font(r, CN_FONT, 10)
    return r


def hrule(doc, color="1F4E79", size=8):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)
    ppr = p._p.get_or_add_pPr()
    bd = OxmlElement("w:pBdr")
    bot = OxmlElement("w:bottom")
    bot.set(qn("w:val"), "single"); bot.set(qn("w:sz"), str(size))
    bot.set(qn("w:space"), "1"); bot.set(qn("w:color"), color)
    bd.append(bot)
    ppr.append(bd)
    return p


_PB = {"pending": False}


def page_break(doc):
    """登记一个分页请求，由下一个块级段落以 pageBreakBefore 兑现。

    不直接插入带分页符的空段落：那样在当前页恰好写满时，空段落自己会落到
    新一页，分页符再把正文推到下一页，于是多出一整页空白。
    """
    _PB["pending"] = True


def apply_break(p):
    if _PB["pending"]:
        p.paragraph_format.page_break_before = True
        _PB["pending"] = False
    return p


# ---------------------------------------------------------------- 文档骨架
doc = Document()

# 页面：A4 纵向，与项目已有 SRS 文档一致
sec = doc.sections[0]
sec.page_width = Emu(7560310)
sec.page_height = Emu(10692130)
sec.left_margin = Emu(1143000)
sec.right_margin = Emu(1143000)
sec.top_margin = Emu(914400)
sec.bottom_margin = Emu(914400)

style_font(doc.styles["Normal"], CN_FONT, 10.5, None, C_TXT)
doc.styles["Normal"].paragraph_format.line_spacing = 1.5

USABLE = 14.6  # cm，正文可用宽度（页面 21cm − 左右边距 3.17cm × 2 = 14.65cm）
USABLE_CM = USABLE

# 封面不显示页眉页脚
sec.different_first_page_header_footer = True

# 页眉页脚
hp = sec.header.paragraphs[0]
hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_run_font(hp.add_run("校园问卷信息调查管理系统 · 数据库设计（ER 图与数据字典）"), CN_FONT, 8.5, False, C_GRAY)

fp = sec.footer.paragraphs[0]
fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_run_font(fp.add_run("第 "), CN_FONT, 8.5, False, C_GRAY)
field(fp, "PAGE", "1")
set_run_font(fp.add_run(" 页 / 共 "), CN_FONT, 8.5, False, C_GRAY)
field(fp, "NUMPAGES", "1")
set_run_font(fp.add_run(" 页"), CN_FONT, 8.5, False, C_GRAY)

# ---------------------------------------------------------------- 封面
para(doc, "", size=10, space_after=0)
para(doc, "", size=10, space_after=0)
para(doc, "校园问卷信息调查管理系统", size=15, bold=True, color=C_GRAY,
     align=WD_ALIGN_PARAGRAPH.CENTER, space_before=70, space_after=14)
para(doc, "数据库设计说明书", size=30, bold=True, color=RGBColor(0x1F, 0x3B, 0x57),
     align=WD_ALIGN_PARAGRAPH.CENTER, space_after=6)
para(doc, "—— ER 图与数据字典", size=16, bold=True, color=RGBColor(0x2E, 0x5A, 0x7D),
     align=WD_ALIGN_PARAGRAPH.CENTER, space_after=10)
hrule(doc, "1F4E79", 10)
para(doc, "基于 deploy/sql 目录下真实建表脚本（MySQL 8.0）逐字段还原", size=10.5, color=C_GRAY,
     align=WD_ALIGN_PARAGRAPH.CENTER, space_before=8, space_after=60)

info = [
    ("项目名称", "校园问卷信息调查管理系统"),
    ("文档名称", "数据库设计说明书（ER 图与数据字典）"),
    ("文档版本", "V1.0"),
    ("编写团队", "G14 小组"),
    ("适用数据库", "MySQL 8.0（utf8mb4 / InnoDB）"),
    ("表数量", "14 张业务表，4 个功能模块"),
    ("数据来源", "deploy/sql/01_schema_auth.sql、02_schema_survey.sql、03_schema_answer.sql"),
    ("编写日期", "2026-11-08"),
]
t = doc.add_table(rows=0, cols=2)
t.alignment = WD_TABLE_ALIGNMENT.CENTER
for k, v in info:
    cells = t.add_row().cells
    cell_text(cells[0], k, 10.5, True, RGBColor(0x1F, 0x3B, 0x57), WD_ALIGN_PARAGRAPH.RIGHT, CN_HEI)
    cell_text(cells[1], v, 10.5, False, C_TXT)
    cells[1].paragraphs[0].paragraph_format.left_indent = Cm(0.35)
set_col_widths(t, [4.2, 10.0])
page_break(doc)

# ---------------------------------------------------------------- 目录
p = doc.add_paragraph()
apply_break(p)
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
set_run_font(p.add_run("目　　录"), CN_HEI, 20, True, RGBColor(0x1F, 0x3B, 0x57))
p.paragraph_format.space_before = Pt(6)
p.paragraph_format.space_after = Pt(4)
hrule(doc, "1F4E79", 8)
para(doc, "", size=6, space_after=4)

toc_p = doc.add_paragraph()
field(toc_p, 'TOC \\o "1-3" \\h \\z \\u', "【目录将在打开文档时自动生成】")

note = para(doc, "提示：若目录未自动刷新，请在此处右键 →「更新域」→「更新整个目录」。",
            size=9, color=C_GRAY, align=WD_ALIGN_PARAGRAPH.CENTER, space_before=18)
page_break(doc)

# ---------------------------------------------------------------- 1 概述
heading(doc, "1  概述", 1)
heading(doc, "1.1  编写目的", 2)
body(doc, "本文档是《校园问卷信息调查管理系统》数据库设计的图形化说明，面向开发人员、测试人员与答辩评委。"
          "文档从实际执行的建表脚本出发，用 ER 图描述 14 张业务表之间的实体关系，再用数据字典逐表列出"
          "每个字段的命名、类型、约束、默认值与业务含义，做到「图能看懂结构、表能落地实现」。")
body(doc, "文档中的全部信息均可追溯到 deploy/sql 目录下的三份建表脚本，未作任何人为补全或推测，"
          "可直接作为后端实体类、Mapper 与接口文档的对照依据。")

heading(doc, "1.2  数据来源", 2)
body(doc, "数据字典由脚本自动解析建表语句生成，保证与真实库结构完全一致，避免手工抄写产生的偏差。")
make_table(doc,
           ["序号", "脚本文件", "包含内容", "表数"],
           [["1", "deploy/sql/01_schema_auth.sql", "认证与权限模块（sys_user 至 sys_user_permission）", "6"],
            ["2", "deploy/sql/02_schema_survey.sql", "问卷管理模块（survey、survey_question、survey_question_option）", "3"],
            ["3", "deploy/sql/03_schema_answer.sql", "答卷与统计模块（answer_sheet、answer_item、survey_draft）", "3"],
            ["4", "deploy/sql/90_cleanup_test_data.sql", "测试数据清理脚本，不含建表语句", "0"]],
           [1.2, 6.0, 7.0, 1.2],
           [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER])
caption(doc, "表 1-1　数据来源脚本清单")

heading(doc, "1.3  设计约定", 2)
body(doc, "在阅读 ER 图与数据字典之前，先明确以下五条贯穿全库的约定，它们决定了图形与文字的表达方式。")
body(doc, "（1）逻辑外键。建表脚本没有声明物理 FOREIGN KEY 约束，表间关系由服务层（MyBatis-Plus + 业务校验）"
          "保证。这一选择换来的是分库分表与数据迁移的灵活性，以及测试环境批量造数的便利；ER 图中用鸦爪线"
          "表达的表间关系，全部是逻辑外键。")
body(doc, "（2）逻辑删除。需要保留历史的表统一使用 deleted 字段（0=正常、1=已删除），MyBatis-Plus 全局拦截器"
          "自动在查询条件中追加 deleted = 0。因此几乎所有业务索引都把 deleted 放在索引前列或第二列。")
body(doc, "（3）公共审计字段。create_by / create_time / update_by / update_time 四件套用于记录数据的产生与变更，"
          "时间字段默认 CURRENT_TIMESTAMP。")
body(doc, "（4）主键与外键类型。全部主键为 BIGINT 无符号自增，外键与之同型，避免隐式类型转换导致索引失效。")
body(doc, "（5）命名规范。表名与字段名统一小写加下划线；唯一索引前缀 uk_，普通索引前缀 idx_；"
          "布尔语义字段用 TINYINT 存储 0/1，不用 BIT。")

heading(doc, "1.4  表清单汇总", 2)
body(doc, "全库共 14 张业务表，按功能划分为 4 个模块，各表字段数量与职责如下表所示。")
rows = []
for i, name in enumerate(TABLES, 1):
    m = S[name]
    rows.append([str(i), name, m["cn"], str(len(m["columns"])), m["module"]])
make_table(doc, ["序号", "表名", "中文名称", "字段数", "所属模块"], rows,
           [1.2, 5.2, 4.2, 1.6, 3.2],
           [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT,
            WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.CENTER])
caption(doc, "表 1-2　业务表清单（共 14 张）")

# 模块说明
heading(doc, "1.5  模块划分", 2)
for m in MODULES:
    n = len([t for t in TABLES if S[t]["module"] == m])
    bullet(doc, "%s（%d 张表）：%s" % (m, n, MODULE_DESC[m]))

# ---------------------------------------------------------------- 2 ER 图
heading(doc, "2  实体关系图（ER 图）", 1)
heading(doc, "2.1  图形约定", 2)
body(doc, "本文档采用 Information Engineering（IE / 鸦爪）记法：主线表示表间关联，线端的「一」与「鸦爪」"
          "分别表示关系的一端与多端；表框内用色块标签标注主键（PK）、外键（FK）、唯一键（UK）与生成列（GC），"
          "右侧标注字段类型。")
make_table(doc,
           ["图例", "含义"],
           [["PK", "主键（Primary Key），BIGINT 无符号自增"],
            ["FK", "外键（Foreign Key），逻辑关联，脚本未建物理约束"],
            ["UK", "唯一键（Unique Key），可为空的唯一列允许多个 NULL"],
            ["GC", "生成列（Generated Column），由表达式自动计算并持久化"],
            ["实线 + 鸦爪", "强关联：子表记录必须指向一条父表记录（1 : N）"],
            ["虚线 + 棕色", "弱关联：冗余字段或可空引用，业务上允许悬空"],
            ["分组框", "按功能模块聚合的表集合，框标题即模块名"]],
           [3.4, 11.6], [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT])
caption(doc, "表 2-1　ER 图图例说明")

heading(doc, "2.2  全局 ER 图", 2)
body(doc, "下图把 14 张表按 4 个模块分区排布，只保留主键、外键与有辨识价值的关键字段，用于快速把握整体骨架；"
          "跨模块的关联（如 survey.create_by 指向 sys_user、answer_sheet.survey_id 指向 survey）以虚线跨区连接。")
bullet(doc, "① 认证与权限（RBAC）：6 张表。sys_user、sys_role、sys_permission 为三张主表，"
            "sys_user_role 与 sys_role_permission 拆分两个多对多，sys_user_permission 提供用户级追加授权。")
bullet(doc, "② 日志审计：2 张表。sys_login_log 记录每次登录尝试（含失败原因），"
            "sys_operation_log 记录关键写操作；两者都只追加、不更新，user_id 可为空（登录名不存在时留空）。")
bullet(doc, "③ 问卷管理：3 张表。survey 为问卷头，向下挂 survey_question（题目），再挂 survey_question_option（选项）。")
bullet(doc, "④ 答卷与统计：3 张表。answer_sheet 为一次作答，answer_item 为其中一道题的答案，"
            "survey_draft 保存未提交的草稿；三者均以 survey_id 与 user_id 定位归属。")
doc.add_picture(str(BASE / "assets" / "er_overview.png"), width=Cm(14.4))
doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
caption(doc, "图 2-1　系统全局 ER 图（4 个模块、14 张表）")
body(doc, "从全局图可以读出一条主脉络：用户登录后创建问卷，问卷由若干题目组成，每题挂若干选项；"
          "学生填写问卷生成答卷头，每个题目一条答案明细；未提交完的作答暂存为草稿。"
          "权限体系独立成组，通过 sys_user_role 与 sys_user_permission 两条路径为用户授权。")

heading(doc, "2.3  认证与权限模块", 2)
body(doc, "该模块包括用户、角色、权限三张主表与三张关联表。角色与权限是多对多关系，由 sys_role_permission 拆分；"
          "用户在角色之外还可通过 sys_user_permission 追加单项权限（图中棕色细线），用于给个别学生临时开放"
          "「创建问卷」能力，而不必新增角色。最终权限集合 = 全部角色的权限并集 ∪ 用户追加权限。")
body(doc, "注意 sys_user_role 与 sys_role_permission 都没有 deleted 字段：授权关系被撤销时直接物理删除，"
          "并在操作日志中留痕，避免关联表被逻辑删除污染唯一索引。")
doc.add_picture(str(BASE / "assets" / "er_auth.png"), width=Cm(13.6))
doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
caption(doc, "图 2-2　认证与权限模块 ER 图（6 张表，全部字段）")

heading(doc, "2.4  问卷管理模块", 2)
body(doc, "问卷与题目是一对多，题目与选项是一对多。施工图 2-3 中，survey_question_option 同时拥有 question_id "
          "与 survey_id 两个外键，其中 survey_id 是刻意的冗余（棕色虚线）：统计「某问卷各选项得票」时"
          "可直接按 survey_id 聚合，省去一次 join 题目表。冗余字段的一致性由服务层在保存题目时同步维护。")
body(doc, "题目表与选项表都不设 deleted 字段。原因是问卷处于草稿状态时允许整体重排题目与选项，"
          "物理删除更简单；已发布的问卷则禁止增删题目，只能暂停或结束，从而避免历史答卷与题面错位。")
doc.add_picture(str(BASE / "assets" / "er_survey.png"), width=Cm(13.2))
doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
caption(doc, "图 2-3　问卷管理模块 ER 图（3 张表，全部字段）")

heading(doc, "2.5  答卷与统计模块", 2)
body(doc, "答卷头 answer_sheet 与答案明细 answer_item 是一对多：一份答卷提交时，每道题生成一行明细。"
          "answer_item 同时冗余 survey_id，使「按问卷统计某题各选项分布」成为单表聚合。")
body(doc, "answer_sheet 上的 uniq_user 是全文最巧妙的一处设计：它是由表达式生成的 STORED 生成列，"
          "当 limit_once = 1 且未删除时取 user_id，否则取 NULL；再配合唯一索引 (survey_id, uniq_user)，"
          "数据库层就强制了「同一问卷每人只能有一份有效答卷」。当问卷允许重复填写（limit_once = 0）时，"
          "生成列恒为 NULL，唯一索引因 NULL 不参与比较而不产生冲突。")
body(doc, "survey_draft 与 survey / user 的关系画成虚线：草稿是弱实体，提交成功后立即删除，"
          "不存在时不影响任何业务，唯一索引 (survey_id, user_id) 保证一人一问卷只有一份草稿。")
doc.add_picture(str(BASE / "assets" / "er_answer.png"), width=Cm(14.4))
doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
caption(doc, "图 2-4　答卷与统计模块 ER 图（4 张表）")

heading(doc, "2.6  实体关系汇总", 2)
body(doc, "下表把 ER 图中的每条连线整理成文字，标注基数与关联字段，便于核对实现。")
rel = [
    ["1", "sys_user → sys_user_role", "1:N", "sys_user.id = sys_user_role.user_id", "一个用户可拥有多个角色"],
    ["2", "sys_role → sys_user_role", "1:N", "sys_role.id = sys_user_role.role_id", "一个角色可分配给多个用户"],
    ["3", "sys_role → sys_role_permission", "1:N", "sys_role.id = sys_role_permission.role_id", "角色拥有的权限项"],
    ["4", "sys_permission → sys_role_permission", "1:N", "sys_permission.id = sys_role_permission.perm_id", "权限被哪些角色引用"],
    ["5", "sys_user → sys_user_permission", "1:N", "sys_user.id = sys_user_permission.user_id", "用户级追加授权"],
    ["6", "sys_permission → sys_user_permission", "1:N", "sys_permission.id = sys_user_permission.perm_id", "追加授权指向的权限项"],
    ["7", "sys_user → sys_user_permission", "1:N", "sys_user.id = sys_user_permission.grant_by", "记录授权操作人"],
    ["8", "sys_user → sys_login_log", "1:N", "sys_user.id = sys_login_log.user_id", "账号不存在时该列为 NULL"],
    ["9", "sys_user → sys_operation_log", "1:N", "sys_user.id = sys_operation_log.user_id", "操作人与操作记录"],
    ["10", "sys_user → survey", "1:N", "sys_user.id = survey.create_by", "教师创建的问卷，归属校验依据"],
    ["11", "survey → survey_question", "1:N", "survey.id = survey_question.survey_id", "问卷包含的题目"],
    ["12", "survey_question → survey_question_option", "1:N", "survey_question.id = survey_question_option.question_id", "题目的选项"],
    ["13", "survey → survey_question_option", "1:N", "survey.id = survey_question_option.survey_id", "冗余外键，便于统计聚合"],
    ["14", "survey → answer_sheet", "1:N", "survey.id = answer_sheet.survey_id", "问卷回收的答卷"],
    ["15", "sys_user → answer_sheet", "1:N", "sys_user.id = answer_sheet.user_id", "填写人，匿名问卷仅内部使用"],
    ["16", "answer_sheet → answer_item", "1:N", "answer_sheet.id = answer_item.sheet_id", "一份答卷的多条答案"],
    ["17", "survey_question → answer_item", "1:N", "survey_question.id = answer_item.question_id", "某题被作答的记录"],
    ["18", "survey → answer_item", "1:N", "survey.id = answer_item.survey_id", "冗余外键，便于统计聚合"],
    ["19", "survey → survey_draft", "1:N", "survey.id = survey_draft.survey_id", "问卷的未提交草稿"],
    ["20", "sys_user → survey_draft", "1:N", "sys_user.id = survey_draft.user_id", "填写人的草稿"],
]
make_table(doc, ["序号", "关系（父 → 子）", "基数", "关联条件", "业务含义"], rel,
           [0.9, 4.9, 1.0, 5.4, 3.8],
           [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER,
            WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT], font_size=8.5)
caption(doc, "表 2-2　实体关系与基数汇总（逻辑外键）")
body(doc, "以上 20 条关系全部为逻辑外键。部署时若需要物理约束，可按本表逐条补充 ALTER TABLE ... "
          "ADD CONSTRAINT 语句；当前设计选择不建物理外键，是为了让测试数据准备与问卷整体重排更灵活。")

# ---------------------------------------------------------------- 3 数据字典
page_break(doc)
heading(doc, "3  数据字典", 1)
body(doc, "数据字典按模块、按表逐字段展开。每张表先给出定位信息（表名、中文名、所属模块、建表脚本），"
          "再给出字段清单与索引约束清单。字段清单中「空」列表示该字段是否允许 NULL，"
          "「默认值」列按脚本原样给出。")

tno = 0
for mi, m in enumerate(MODULES, 1):
    heading(doc, "3.%d  %s模块" % (mi, m), 2)
    body(doc, MODULE_DESC[m])
    names = [t for t in TABLES if S[t]["module"] == m]

    for ti, name in enumerate(names, 1):
        meta = S[name]
        heading(doc, "3.%d.%d  %s（%s）" % (mi, ti, name, meta["cn"]), 3)

        # 定位信息小表
        mid = doc.add_table(rows=0, cols=4)
        mid.alignment = WD_TABLE_ALIGNMENT.CENTER
        for k1, v1, k2, v2 in [
            ("表名", name, "中文名称", meta["cn"]),
            ("所属模块", m, "字段数量", "%d 个" % len(meta["columns"])),
            ("建表脚本", meta["file"], "索引数量", "%d 个" % len(meta["indexes"])),
        ]:
            cells = mid.add_row().cells
            cell_text(cells[0], k1, 9, True, RGBColor(0x1F, 0x3B, 0x57), WD_ALIGN_PARAGRAPH.CENTER, CN_HEI)
            shade(cells[0], SUB_FILL)
            cell_text(cells[1], v1, 9, False, C_TXT)
            cell_text(cells[2], k2, 9, True, RGBColor(0x1F, 0x3B, 0x57), WD_ALIGN_PARAGRAPH.CENTER, CN_HEI)
            shade(cells[2], SUB_FILL)
            cell_text(cells[3], v2, 9, False, C_TXT)
        set_col_widths(mid, [2.4, 5.4, 2.4, 5.3])

        para(doc, "", size=4, space_after=2)

        # 字段表
        rows = []
        for c in meta["columns"]:
            keys = c["keys"]
            kt = "PK" if "PK" in keys else ""
            fx = "FK" if "FK" in keys else ""
            uk = "UK" if ("UK" in keys or "UQ*" in keys) else ""
            if c.get("auto_increment"):
                extra = "自增"
            elif "GENERATED" in c["type"].upper():
                extra = "生成列"
            else:
                extra = ""
            keytxt = " ".join(x for x in (kt, fx, uk) if x)
            if extra and keytxt:
                keytxt += " " + extra
            elif extra:
                keytxt = extra
            rows.append([c["name"], c["type"], "否" if c["not_null"] else "是",
                         "-" if c["default"] in ("", None) else c["default"],
                         keytxt or "-", c["comment"]])
        tno += 1
        ft = make_table(doc, ["字段名", "数据类型", "非空", "默认值", "键", "说明"], rows,
                        [3.1, 2.6, 1.1, 2.9, 1.4, 3.5],
                        [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER,
                         WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT],
                        font_size=8.5)
        # 长字段名与长默认值（CURRENT_TIMESTAMP ON UPDATE ...）缩小字号，避免单词被折断
        for row in ft.rows[1:]:
            for idx, limit, pt in ((0, 17, 7.5), (1, 8, 7.5), (3, 15, 7.5)):
                cell = row.cells[idx]
                if len(cell.text) >= limit:
                    for pp in cell.paragraphs:
                        for rr in pp.runs:
                            rr.font.size = Pt(pt)
        keep_with_caption(ft)
        caption(doc, "表 %d　%s 字段说明" % (tno, name), after=8)

        # 索引表
        irows = []
        for ix in meta["indexes"]:
            kind = "主键" if ix["primary"] else ("唯一" if ix["unique"] else "普通")
            irows.append([ix["name"], ", ".join(ix["cols"]), kind])
        tno += 1
        it = make_table(doc, ["索引名", "索引字段", "类型"], irows,
                        [5.0, 7.6, 2.9],
                        [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER],
                        font_size=9)
        keep_with_caption(it)
        caption(doc, "表 %d　%s 索引与约束" % (tno, name), after=10)

# ---------------------------------------------------------------- 4 关键设计
page_break(doc)
heading(doc, "4  关键设计说明", 1)
body(doc, "本章解释数据字典里那些「看起来多余」或「不太直观」的设计，说明它们分别解决了什么问题。"
          "答辩时被问到细节，可以直接引用本章。")

heading(doc, "4.1  逻辑删除与物理删除的取舍", 2)
body(doc, "全库有三类删除策略，选择依据是「删除后是否需要保留可追溯的历史」。")
make_table(doc,
           ["策略", "适用表", "实现方式", "理由"],
           [["逻辑删除", "sys_user、survey、answer_sheet",
             "deleted 字段（0 正常 / 1 已删除），查询自动追加 deleted = 0",
             "用户误删问卷需要可恢复；答卷作废需留档，不能真的从统计里消失"],
            ["物理删除", "sys_user_role、sys_role_permission、sys_user_permission、survey_question、survey_question_option",
             "直接 DELETE",
             "关联表被逻辑删除会污染唯一索引（同一用户同一角色只能有一行）；题目与选项只在草稿期可改，重排时整体重建最干净"],
            ["物理删除 + 归档", "survey_draft", "提交成功后立即 DELETE",
             "草稿是临时态，保留反而会造成「已提交却仍有草稿」的状态不一致"]],
           [2.3, 5.0, 4.3, 3.9],
           [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT],
           font_size=8.5)
caption(doc, "表 4-1　三类删除策略")

heading(doc, "4.2  限填一次：用生成列 + 唯一索引兜底", 2)
body(doc, "「每人限填一次」如果只靠服务层先查后插，在并发提交下会漏判——两个请求同时通过校验，"
          "各自插入一份答卷。数据库层必须有兜底，否则高并发场景（硬性指标要求 500 并发）会出现重复答卷。")
body(doc, "answer_sheet.uniq_user 的定义是：CASE WHEN limit_once = 1 AND deleted = 0 THEN user_id ELSE NULL END，"
          "类型为 STORED 生成列。配合唯一索引 uk_survey_user(survey_id, uniq_user)：")
bullet(doc, "限填一次 + 未删除 → uniq_user = user_id，唯一索引生效，(survey_id, user_id) 只能出现一次；")
bullet(doc, "允许重复填写 → limit_once = 0，uniq_user 为 NULL，MySQL 唯一索引不比较 NULL，可插入任意多份；")
bullet(doc, "答卷被逻辑删除 → deleted = 1，uniq_user 变 NULL，槽位自动释放，用户可重新填写。")
body(doc, "limit_once 字段是对 survey.limit_once 的快照而非实时读取，原因是发布者可能在收卷中途把问卷从"
          "「限填一次」改为「可重复」，若实时读取，已有答卷的槽位含义会被追溯性改写，导致唯一索引冲突或失效。"
          "快照保证「提交那一刻的规则决定这份答卷是否占用槽位」。")

heading(doc, "4.3  冗余字段：用空间换掉 N+1", 2)
body(doc, "表里有三处刻意的冗余，每一处都对应一个明确的性能问题。")
make_table(doc,
           ["冗余字段", "所在表", "避免的开销", "一致性维护"],
           [["question_count", "survey", "问卷列表页逐行 count(*) 题目表", "题目增删时由服务层同步 ±1"],
            ["answer_count", "survey", "列表页统计答卷数；语义是「有效答卷数」", "提交 +1，标记无效或删除时扣减"],
            ["survey_id", "survey_question_option、answer_item",
             "按问卷统计选项分布时多一次 join 题目表", "写入时与父记录同值，父记录不可变故无需回填"]],
           [3.4, 4.4, 4.4, 3.3],
           [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT],
           font_size=8.5)
caption(doc, "表 4-2　冗余字段及其一致性策略")

heading(doc, "4.4  JSON 字段的用法", 2)
body(doc, "库内有两处 JSON：answer_item.option_values 与 survey_draft.answers_json。前者是选项 ID 数组"
          "（如 [12,15]），单选也统一存数组，使单选与多选共用一套取值逻辑；后者是整卷作答快照，"
          "结构与提交请求一致，允许缺题，用于断点续答。")
body(doc, "把选项答案存 JSON 而不是拆成 answer_item_option 关联表，是为了让「一份答卷的答案条数 = 题目数」，"
          "统计时按 question_id 分组后直接用 JSON 函数展开即可，避免跨表 join 带来的行数放大。")

heading(doc, "4.5  匿名问卷的隐私边界", 2)
body(doc, "survey.anonymous = 1 时，answer_sheet 仍然写入 user_id，因为「限填一次」与「我的填写记录」"
          "两个功能都依赖它。隐私边界靠接口层控制：任何面向发布者的查询都不得返回 user_id 字段，"
          "导出答卷时同样剔除。")
body(doc, "为此 answer_sheet 特意不记录 ip 与 user_agent——答卷表一旦存了 IP，匿名承诺就形同虚设。"
          "需要追溯访问来源时，从 sys_login_log 侧查询，与答卷数据物理隔离。")

heading(doc, "4.6  状态机与时间字段", 2)
body(doc, "survey.status 只有 DRAFT、PUBLISHED、PAUSED、CLOSED 四个值，删除不是状态而是 deleted 标记。"
          "这样「已删除的已发布问卷」不会与「已结束的问卷」混淆。")
make_table(doc,
           ["状态", "含义", "可执行操作", "时间字段"],
           [["DRAFT", "草稿，仅创建人可见", "编辑题目、发布、删除", "create_time"],
            ["PUBLISHED", "已发布，可填写", "暂停、提前结束", "published_time"],
            ["PAUSED", "已暂停，拒绝新答卷", "恢复发布、结束", "—"],
            ["CLOSED", "已结束，只读", "查看统计、导出", "closed_time"]],
           [2.6, 5.0, 4.6, 3.3],
           [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT],
           font_size=8.5)
caption(doc, "表 4-3　问卷状态机")
body(doc, "start_time 与 end_time 表达「定时开放」和「到期自动关闭」，二者都可为 NULL：NULL 表示无限制。"
          "发布时若填了 end_time，则查询有效问卷的条件是 now() 落在 [start_time, end_time] 内，"
          "并由定时任务把过期问卷置为 CLOSED 并写入 closed_time。")

# ---------------------------------------------------------------- 附录
page_break(doc)
heading(doc, "附录 A　枚举与取值字典", 1)
body(doc, "以下枚举值均取自建表脚本的字段注释，是接口参数校验与前端下拉框的唯一依据。")
enum_rows = [
    ["sys_user.status", "TINYINT", "1 正常；0 禁用"],
    ["sys_user.audit_status", "TINYINT", "0 待审核；1 已通过；2 已驳回（管理员导入的账号默认已通过）"],
    ["sys_user.must_change_password", "TINYINT", "1 下次登录强制改密；0 否"],
    ["sys_user.deleted", "TINYINT", "0 未删；1 已删"],
    ["sys_role.code", "VARCHAR(32)", "ROLE_ADMIN 管理员；ROLE_TEACHER 教师；ROLE_STUDENT 学生"],
    ["sys_role.builtin", "TINYINT", "1 内置角色，不可删除；0 自定义"],
    ["sys_permission.code", "VARCHAR(64)", "「模块:动作[:范围]」，如 survey:create、survey:publish:self"],
    ["sys_login_log.result", "TINYINT", "1 成功；0 失败"],
    ["sys_operation_log.result", "TINYINT", "1 成功；0 失败"],
    ["survey.category", "VARCHAR(32)", "TEACHING 教学反馈；ACTIVITY 活动报名；SATISFACTION 满意度；OTHER 其他"],
    ["survey.anonymous", "TINYINT", "1 匿名；0 实名"],
    ["survey.status", "VARCHAR(16)", "DRAFT 草稿；PUBLISHED 已发布；PAUSED 已暂停；CLOSED 已结束"],
    ["survey.limit_once", "TINYINT", "1 每人一份；0 可重复填写"],
    ["survey.deleted", "TINYINT", "0 正常；1 已删除"],
    ["survey_question.question_type", "VARCHAR(16)", "SINGLE 单选；MULTIPLE 多选；TEXT 单行文本；TEXTAREA 多行文本；RATING 评分"],
    ["survey_question.is_required", "TINYINT", "1 必答；0 选答"],
    ["answer_sheet.limit_once", "TINYINT", "提交时对 survey.limit_once 的快照"],
    ["answer_sheet.is_valid", "TINYINT", "1 有效（计入统计）；0 已标记无效"],
    ["answer_sheet.deleted", "TINYINT", "0 正常；1 已删除"],
]
brk_col(enum_rows, 0)
make_table(doc, ["字段", "类型", "取值与含义"], enum_rows,
           [5.5, 2.2, 6.9],
           [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT],
           font_size=8.5)
caption(doc, "表 A-1　枚举与取值字典")

heading(doc, "附录 B　索引设计说明", 1)
body(doc, "索引不是字段清单的附属品，每一条都对应一个具体查询。下表按「查询场景 → 命中索引」的方式列出全库索引的设计意图。")
idx_rows = [
    ["用户登录", "sys_user", "uk_username", "按账号精确查，登录必经路径"],
    ["用户列表按状态筛选", "sys_user", "idx_status(status, deleted)", "配合逻辑删除，避免回表"],
    ["待审核用户列表", "sys_user", "idx_audit(idx_audit_status, deleted)", "管理员审核页"],
    ["按手机号查找", "sys_user", "idx_phone", "找回账号、去重校验"],
    ["登录审计按账号+时间", "sys_login_log", "idx_username_time", "安全审计页的倒序分页"],
    ["登录审计按时间", "sys_login_log", "idx_time", "全量时间范围查询"],
    ["操作日志按人+时间", "sys_operation_log", "idx_user_time", "追溯某人的操作"],
    ["操作日志按模块+时间", "sys_operation_log", "idx_module_time", "按模块巡检"],
    ["分享链接直达问卷", "survey", "uk_share_code", "8 位分享码，可空唯一，允许多 NULL"],
    ["我的问卷列表", "survey", "idx_owner(create_by, status, deleted)", "教师工作台核心查询"],
    ["问卷大厅（已发布且未过期）", "survey", "idx_status_time(status, end_time)", "学生浏览可填问卷"],
    ["问卷按标题模糊搜", "survey", "idx_title", "前缀匹配可用，注意避免 %开头"],
    ["问卷新到排序", "survey", "idx_create_time", "首页列表"],
    ["题目按序加载", "survey_question", "idx_survey_sort(survey_id, sort_no)", "填写页与预览页"],
    ["选项按序加载", "survey_question_option", "idx_question_sort(question_id, sort_no)", "渲染题目选项"],
    ["选项按问卷聚合", "survey_question_option", "idx_survey", "统计各选项得票"],
    ["限填一次兜底", "answer_sheet", "uk_survey_user(survey_id, uniq_user)", "数据库层唯一性保证"],
    ["答卷列表分页", "answer_sheet", "idx_survey_time(survey_id, deleted, create_time)", "发布者查看答卷"],
    ["我的填写记录", "answer_sheet", "idx_user_time(user_id, create_time)", "学生端历史答卷"],
    ["答案按题聚合", "answer_item", "uk_sheet_question(sheet_id, question_id)", "一题一答，兼作唯一约束"],
    ["统计某题分布", "answer_item", "idx_survey_question(survey_id, question_id)", "单表聚合，免 join"],
    ["答案按答卷加载", "answer_item", "idx_sheet", "查看单份答卷详情"],
    ["草稿读写", "survey_draft", "uk_survey_user(survey_id, user_id)", "一人一问卷一份草稿"],
    ["我的草稿", "survey_draft", "idx_user_time(user_id, update_time)", "断点续答入口"],
]
brk_col(idx_rows, 1)
brk_col(idx_rows, 2)
make_table(doc, ["查询场景", "表", "索引", "设计意图"], idx_rows,
           [3.2, 3.2, 4.6, 3.6],
           [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT],
           font_size=8.5)
caption(doc, "表 B-1　索引设计与查询场景对照", after=2)

para(doc, "— 文档结束 —", size=9.5, color=C_GRAY, align=WD_ALIGN_PARAGRAPH.CENTER, space_before=14)

# ---------------------------------------------------------------- 域更新设置
settings = doc.settings.element
uf = OxmlElement("w:updateFields")
uf.set(qn("w:val"), "true")
settings.append(uf)

doc.save(str(OUT))
print("saved:", OUT, OUT.stat().st_size, "bytes")
print("段落数:", len(doc.paragraphs), " 表格数:", len(doc.tables))
