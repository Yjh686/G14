# -*- coding: utf-8 -*-
"""把 ER 图与数据字典注入桌面的《SRS需求分析报告(2).docx》第 4 章。

改动范围：
  * 4.2 数据模型：ER 实体联系简述  → 改为 4.2.1~4.2.6（图例 / 全局图 / 三个模块图 / 关系汇总）
  * 4.4 数据字典                  → 改为 4.4.1~4.4.7（脚本清单 / 表清单 / 逐表字段与索引 / 枚举 / 索引场景）

样式对齐 SRS 既有风格：Heading 1/2/3 沿用文档自带样式；正文 Normal (Web) 12pt；
表格复用第 2 章表格的边框（上下细线 + 行间 BFBFBF）、F2F2F2 表头、9pt 正文。
原 4.2 的条目式实体清单、4.4 的「参见可行性报告」占位文字被替换，其余章节不动。
"""
import json
import shutil
from pathlib import Path

import docx
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement, parse_xml
from docx.oxml.ns import nsdecls, qn
from docx.shared import Cm, Pt, RGBColor

BASE = Path(r"D:\G14(1)\docs\design\erd")
SRC = Path(r"C:\Users\25347\Desktop\SRS需求分析报告(2).docx")
BAK = BASE / "backup" / "SRS需求分析报告(2).orig.docx"
S = json.loads((BASE / "schema.json").read_text(encoding="utf-8"))

CN = "宋体"
GREY = RGBColor(0x59, 0x59, 0x59)
USABLE = 14.6  # cm，正文可用宽度（21 − 3.17×2）

MODULES = ["认证与权限", "日志审计", "问卷管理", "答卷与统计"]
MODULE_DESC = {
    "认证与权限": "RBAC 三元组（用户、角色、权限）及其两张关联表，另加一张用户级追加权限表，"
                  "用于给个别学生临时授予「创建问卷」能力而不新增角色。",
    "日志审计": "登录日志与操作日志，只追加不修改，用于安全审计与问题追溯。",
    "问卷管理": "问卷主表、题目表、选项表，覆盖创建、编辑、发布、暂停、结束的全过程。",
    "答卷与统计": "答卷头、答案明细、填写草稿，支撑在线填写、断点续答、统计聚合与导出。",
}
_ORDER = ["sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
          "sys_user_permission", "sys_login_log", "sys_operation_log",
          "survey", "survey_question", "survey_question_option",
          "answer_sheet", "answer_item", "survey_draft"]
assert set(_ORDER) == set(S.keys()), set(S.keys()) ^ set(_ORDER)
TABLES = [t for m in MODULES for t in _ORDER if S[t]["module"] == m]

# ------------------------------------------------------------------ 备份 + 装载
if not BAK.exists():
    BAK.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SRC, BAK)
    print("backup ->", BAK)
else:
    # 每次运行都从未注入的原始文档重建，保证脚本可重复执行（幂等）
    shutil.copy2(BAK, SRC)
    print("restore from backup ->", BAK)

doc = docx.Document(str(SRC))
body = doc.element.body
SECTPR = body.find(qn("w:sectPr"))


def tail_mark():
    """记录当前正文末尾元素（sectPr 之前），用于识别后续新增块。"""
    kids = [c for c in body.iterchildren() if c is not SECTPR]
    return kids[-1] if kids else None


def blocks_after(mark):
    """返回 mark 之后、sectPr 之前的全部块级元素（按文档顺序）。"""
    out, grabbed = [], mark is None
    for c in body.iterchildren():
        if c is SECTPR:
            break
        if grabbed:
            out.append(c)
        elif c is mark:
            grabbed = True
    return out


# ------------------------------------------------------------------ 基础工具
ZW = "\u200b"


def brk(s):
    out = []
    for ch in str(s):
        out.append(ch)
        if ch in "._(,/":
            out.append(ZW)
    return "".join(out)


def run_font(run, size=None, bold=None, color=None, mono=False):
    if mono:
        run.font.name = "Consolas"
        rpr = run._element.get_or_add_rPr()
        rf = rpr.find(qn("w:rFonts"))
        if rf is not None:
            rf.set(qn("w:eastAsia"), CN)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if color is not None:
        run.font.color.rgb = color


def P(text="", size=12, style="Normal (Web)", bold=False, color=None, align=None,
      before=0, after=6, first_indent=None, left=None, hanging=None,
      keep_next=False, page_break=False, mono=False):
    p = doc.add_paragraph()
    try:
        p.style = doc.styles[style]
    except KeyError:
        pass
    pf = p.paragraph_format
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    # 样式 Normal (Web) 带 beforeAutospacing/afterAutospacing=1，Word 会用它覆盖
    # 上面设的 w:before/w:after，凭空多出十几磅。必须显式关掉。
    ppr = p._p.pPr
    sp = ppr.find(qn("w:spacing")) if ppr is not None else None
    if sp is not None:
        sp.set(qn("w:beforeAutospacing"), "0")
        sp.set(qn("w:afterAutospacing"), "0")
    if first_indent is not None:
        pf.first_line_indent = Pt(first_indent)
    if left is not None:
        pf.left_indent = Pt(left)
    if hanging is not None:
        pf.first_line_indent = Pt(-hanging)
    if align is not None:
        p.alignment = align
    if keep_next:
        pf.keep_with_next = True
    if page_break:
        pf.page_break_before = True
    if text:
        run_font(p.add_run(text), size, bold, color, mono)
    else:
        # 空段落的高度由「段落标记」的字号决定，不设就会回到 12pt，
        # 在表间当垫片用时会留下约 19pt 的可见空档。
        ppr = p._p.get_or_add_pPr()
        rpr = ppr.find(qn("w:rPr"))
        if rpr is None:
            rpr = OxmlElement("w:rPr")
            ppr.append(rpr)
        for tag in ("w:sz", "w:szCs"):
            el = OxmlElement(tag)
            el.set(qn("w:val"), str(int(round(size * 2))))
            rpr.append(el)
    return p


def BODY(text):
    return P(text, size=12, after=6, align=WD_ALIGN_PARAGRAPH.JUSTIFY)


def BULLET(text):
    p = P("", size=12, after=3, left=36, hanging=18, align=WD_ALIGN_PARAGRAPH.JUSTIFY)
    run_font(p.add_run("· "), 12, False, GREY)
    run_font(p.add_run(text), 12)
    return p


def CAP(text, before=4, after=10):
    return P(text, size=10.5, bold=True, color=GREY, align=WD_ALIGN_PARAGRAPH.CENTER,
             before=before, after=after)


def HEAD(text, level):
    h = doc.add_heading(level=level)
    run_font(h.add_run(text))
    pf = h.paragraph_format
    pf.space_before = Pt(12 if level <= 2 else 8)
    pf.space_after = Pt(6 if level <= 2 else 4)
    pf.keep_with_next = True
    return h


def ensure_heading4():
    """SRS 原文档只有 Heading 1-3；补一个 Heading 4（大纲级别 4，不进 1-3 级目录）。"""
    try:
        return doc.styles["Heading 4"]
    except KeyError:
        pass
    styles_el = doc.styles.element
    xml = ('<w:style %s w:type="paragraph" w:styleId="Heading4">'
           '<w:name w:val="heading 4"/><w:basedOn w:val="4"/><w:next w:val="1"/>'
           '<w:qFormat/><w:uiPriority w:val="0"/>'
           '<w:pPr><w:keepNext/><w:spacing w:before="180" w:after="60" w:line="240" w:lineRule="auto"/>'
           '<w:ind w:left="0"/><w:jc w:val="left"/><w:outlineLvl w:val="3"/></w:pPr>'
           '<w:rPr><w:rFonts w:hint="eastAsia" w:ascii="%s" w:hAnsi="%s" w:eastAsia="%s" w:cs="%s"/>'
           '<w:b/><w:bCs/><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr></w:style>'
           % (nsdecls("w"), CN, CN, CN, CN))
    styles_el.append(parse_xml(xml))
    return doc.styles["Heading 4"]


H4 = ensure_heading4()


def HEAD4(text):
    p = doc.add_paragraph(style=H4)
    run_font(p.add_run(text))
    p.paragraph_format.keep_with_next = True
    return p


# ------------------------------------------------------------------ 表格
BORDERS = (
    '<w:tblBorders>'
    '<w:top w:val="single" w:color="000000" w:sz="8" w:space="0"/>'
    '<w:left w:val="none" w:color="auto" w:sz="0" w:space="0"/>'
    '<w:bottom w:val="single" w:color="000000" w:sz="8" w:space="0"/>'
    '<w:right w:val="none" w:color="auto" w:sz="0" w:space="0"/>'
    '<w:insideH w:val="single" w:color="BFBFBF" w:sz="4" w:space="0"/>'
    '<w:insideV w:val="none" w:color="auto" w:sz="0" w:space="0"/>'
    '</w:tblBorders>'
)
CELLMAR = ('<w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="110" w:type="dxa"/>'
           '<w:bottom w:w="40" w:type="dxa"/><w:right w:w="110" w:type="dxa"/></w:tblCellMar>')


def srs_tblpr(t):
    """套用 SRS 第 2 章表格的外观：无竖线、上下粗线、行间细灰线、单元格窄边距。"""
    tbl = t._tbl
    old = tbl.tblPr
    if old is not None:
        tbl.remove(old)
    xml = ('<w:tblPr %s><w:tblStyle w:val="9"/><w:tblW w:w="5000" w:type="pct"/>'
           '<w:tblInd w:w="0" w:type="dxa"/>%s<w:tblLayout w:type="fixed"/>%s</w:tblPr>'
           % (nsdecls("w"), BORDERS, CELLMAR))
    tbl.insert(0, parse_xml(xml))


def cell_fmt(cell, text, size=9, bold=False, align=WD_ALIGN_PARAGRAPH.LEFT,
             fill=None, mono=False):
    cell.text = ""
    p = cell.paragraphs[0]
    p.alignment = align
    pf = p.paragraph_format
    pf.space_before = Pt(0)
    pf.space_after = Pt(0)
    pf.line_spacing = 1.0
    if text not in (None, ""):
        run_font(p.add_run(str(text)), size, bold, None, mono)
    cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
    if fill:
        tcpr = cell._tc.get_or_add_tcPr()
        shd = OxmlElement("w:shd")
        shd.set(qn("w:val"), "clear")
        shd.set(qn("w:color"), "auto")
        shd.set(qn("w:fill"), fill)
        tcpr.append(shd)


def set_widths(t, cm_list):
    total = sum(cm_list)
    k = USABLE / total
    cm_list = [w * k for w in cm_list]
    t.autofit = False
    for row in t.rows:
        for i, w in enumerate(cm_list):
            row.cells[i].width = Cm(w)
    for i, w in enumerate(cm_list):
        t.columns[i].width = Cm(w)
    # tblW 必须写死 dxa：若沿用 SRS 原有的 5000 pct，Word 会按「整页宽度」重算，
    # 把表格撑到 15.04cm 而溢出右边距。
    tblw = t._tbl.tblPr.find(qn("w:tblW"))
    if tblw is None:
        tblw = OxmlElement("w:tblW")
        t._tbl.tblPr.append(tblw)
    tblw.set(qn("w:type"), "dxa")
    tblw.set(qn("w:w"), str(int(round(sum(cm_list) / 2.54 * 1440))))


def repeat_header(row):
    trpr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:tblHeader")
    el.set(qn("w:val"), "true")
    trpr.append(el)


def cant_split(row):
    trpr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:cantSplit")
    el.set(qn("w:val"), "true")
    trpr.append(el)


def TABLE(header, rows, cm_list, aligns=None, font=9, mono_cols=(), small=None):
    """small: [(col, min_len, pt)]，超长单元格缩字号避免折断。"""
    t = doc.add_table(rows=1, cols=len(header))
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = t.rows[0]
    repeat_header(hdr)
    cant_split(hdr)
    for i, h in enumerate(header):
        cell_fmt(hdr.cells[i], h, font, True, WD_ALIGN_PARAGRAPH.CENTER, fill="F2F2F2")
        for cp in hdr.cells[i].paragraphs:
            cp.paragraph_format.keep_with_next = True
    for r in rows:
        nr = t.add_row()
        cant_split(nr)
        for ci, v in enumerate(r):
            al = aligns[ci] if aligns else WD_ALIGN_PARAGRAPH.LEFT
            cell_fmt(nr.cells[ci], v, font, False, al, mono=(ci in mono_cols))
    srs_tblpr(t)
    set_widths(t, cm_list)
    if small:
        for row in t.rows[1:]:
            for idx, limit, pt in small:
                if len(row.cells[idx].text) >= limit:
                    for pp in row.cells[idx].paragraphs:
                        for rr in pp.runs:
                            rr.font.size = Pt(pt)
    for cell in t.rows[-1].cells:
        for cp in cell.paragraphs:
            cp.paragraph_format.keep_with_next = True
    return t


def PICTURE(name, cm_w, cap):
    pic = doc.add_paragraph()
    pic.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pic.paragraph_format.space_before = Pt(6)
    pic.paragraph_format.space_after = Pt(2)
    pic.paragraph_format.keep_with_next = True
    pic.add_run().add_picture(str(BASE / "assets" / name), width=Cm(cm_w))
    CAP(cap, before=2, after=10)


# ------------------------------------------------------------------ 定位章节
def find(text):
    """按章节号定位标题段落，容忍中英文间空格数量与标题被改写过的情况。"""
    import re
    pat = re.compile(r"^" + re.escape(text.split()[0]) + r"\s*" + re.escape(text.split()[1]))
    for p in doc.paragraphs:
        t = p.text.strip()
        if t.startswith(text) or pat.match(t):
            return p
    return None


h42, h43, h44, h5 = find("4.2 数据模型"), find("4.3 行为模型"), find("4.4 数据字典"), find("5 其他需求")
for _h, _n in ((h42, "4.2"), (h43, "4.3"), (h44, "4.4"), (h5, "5")):
    assert _h is not None, "找不到章节 " + _n


def clear_between(start_p, stop_p):
    """删除 start_p 之后、stop_p 之前的全部块级元素。"""
    n, cur = 0, start_p._p.getnext()
    while cur is not None and cur is not stop_p._p:
        nxt = cur.getnext()
        cur.getparent().remove(cur)
        n += 1
        cur = nxt
    return n


print("cleared under 4.2:", clear_between(h42, h43))
print("cleared under 4.4:", clear_between(h44, h5))


def set_title(p, text):
    for r in p.runs:
        r.text = ""
    if p.runs:
        p.runs[0].text = text
    else:
        p.add_run(text)


set_title(h42, "4.2  数据模型：ER 图")
set_title(h44, "4.4  数据字典（14 张业务表）")

# ================================================================== 4.2
mark = tail_mark()
BODY("本节 ER 图由 deploy/sql 目录下的三份建表脚本（01_schema_auth.sql、02_schema_survey.sql、"
     "03_schema_answer.sql）自动解析生成，图中每张表、每个字段都与实际建表语句一一对应，不是示意图。"
     "建议阅读顺序：先看 4.2.2 全局图建立整体印象，再按模块看 4.2.3~4.2.5 的细节，"
     "最后用 4.2.6 的关系表核对后端实体的关联写法。")

HEAD("4.2.1  图形约定", 3)
BODY("ER 图采用 Information Engineering（IE / 鸦爪）记法：表框内的色块标签标注字段在键结构中的角色，"
     "框间连线一端为「一」、另一端为「鸦爪」即表示一对多。全部关系均为逻辑外键——建表脚本没有声明"
     "物理 FOREIGN KEY 约束，参照完整性由服务层（MyBatis-Plus 全局拦截器 + 业务校验）保证。")
TABLE(["图例", "含义"],
      [["PK", "主键（Primary Key），BIGINT 无符号自增"],
       ["FK", "外键（Foreign Key），逻辑关联，脚本未建物理约束"],
       ["UK", "唯一键（Unique Key），可为空的唯一列允许多个 NULL"],
       ["GC", "生成列（Generated Column），由表达式自动计算并持久化"],
       ["实线 + 鸦爪", "强关联：子表记录必须指向一条父表记录（1:N）"],
       ["虚线 + 棕色", "弱关联：冗余字段或可空引用，业务上允许悬空"],
       ["分组框", "按功能模块聚合的表集合，框标题即模块名"]],
      [3.4, 11.1], [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT])
CAP("表 4-1  ER 图图例说明")

HEAD("4.2.2  全局 ER 图", 3)
BODY("下图把 14 张业务表按 4 个模块分区排布，只保留主键、外键与有辨识价值的关键字段，用于快速把握整体骨架；"
     "跨模块关联（如 survey.create_by 指向 sys_user、answer_sheet.survey_id 指向 survey）以虚线跨区连接。")
BULLET("① 认证与权限（RBAC）：6 张表。sys_user、sys_role、sys_permission 为三张主表，"
       "sys_user_role 与 sys_role_permission 拆分两个多对多，sys_user_permission 提供用户级追加授权。")
BULLET("② 日志审计：2 张表。sys_login_log 记录每次登录尝试（含失败原因），sys_operation_log 记录关键写操作；"
       "两者都只追加不更新，user_id 可为空（登录名不存在时留空）。")
BULLET("③ 问卷管理：3 张表。survey 为问卷头，向下挂 survey_question（题目），再挂 survey_question_option（选项）。")
BULLET("④ 答卷与统计：3 张表。answer_sheet 为一次作答，answer_item 为其中一道题的答案，"
       "survey_draft 保存未提交的草稿；三者均以 survey_id 与 user_id 定位归属。")
PICTURE("er_overview.png", 14.0, "图 4-1  系统全局 ER 图（4 个模块、14 张表）")
BODY("从全局图可以读出一条主脉络：用户登录后创建问卷，问卷由若干题目组成，每题挂若干选项；"
     "学生填写问卷生成答卷头，每个题目一条答案明细；未提交完的作答暂存为草稿。"
     "权限体系独立成组，通过 sys_user_role 与 sys_user_permission 两条路径为用户授权。")

HEAD("4.2.3  认证与权限模块 ER 图", 3)
BODY("该模块包括用户、角色、权限三张主表与三张关联表。角色与权限是多对多，由 sys_role_permission 拆分；"
     "用户在角色之外还可通过 sys_user_permission 追加单项权限（图中棕色细线），用于给个别学生临时开放"
     "「创建问卷」能力而不必新增角色。最终权限集合 = 全部角色的权限并集 ∪ 用户追加权限。")
BODY("注意 sys_user_role 与 sys_role_permission 都没有 deleted 字段：授权关系被撤销时直接物理删除，"
     "并在操作日志中留痕，避免关联表被逻辑删除污染唯一索引。")
PICTURE("er_auth.png", 13.4, "图 4-2  认证与权限模块 ER 图（6 张表，全部字段）")

HEAD("4.2.4  问卷管理模块 ER 图", 3)
BODY("问卷与题目是一对多，题目与选项是一对多。survey_question_option 同时拥有 question_id 与 survey_id "
     "两个外键，其中 survey_id 是刻意的冗余（棕色虚线）：统计「某问卷各选项得票」时可直接按 survey_id 聚合，"
     "省去一次 join 题目表；冗余值由服务层在保存题目时同步维护。")
BODY("题目表与选项表都不设 deleted 字段。问卷处于草稿状态时允许整体重排题目与选项，物理删除更简单；"
     "已发布的问卷则禁止增删题目，只能暂停或结束，从而避免历史答卷与题面错位。")
PICTURE("er_survey.png", 13.0, "图 4-3  问卷管理模块 ER 图（3 张表，全部字段）")

HEAD("4.2.5  答卷与统计模块 ER 图", 3)
BODY("答卷头 answer_sheet 与答案明细 answer_item 是一对多：一份答卷提交时，每道题生成一行明细。"
     "answer_item 同时冗余 survey_id，使「按问卷统计某题各选项分布」成为单表聚合。")
BODY("answer_sheet.uniq_user 是全库最巧妙的一处设计：它是由表达式生成的 STORED 生成列，当 limit_once = 1 "
     "且未删除时取 user_id，否则取 NULL；再配合唯一索引 (survey_id, uniq_user)，数据库层就强制了"
     "「同一问卷每人只能有一份有效答卷」。当问卷允许重复填写（limit_once = 0）时，生成列恒为 NULL，"
     "唯一索引因 NULL 不参与比较而不产生冲突。")
BODY("survey_draft 与 survey / user 的关系画成虚线：草稿是弱实体，提交成功后立即删除，不存在时不影响任何业务，"
     "唯一索引 (survey_id, user_id) 保证一人一问卷只有一份草稿。")
PICTURE("er_answer.png", 14.4, "图 4-4  答卷与统计模块 ER 图（4 张表）")

HEAD("4.2.6  实体关系汇总", 3)
BODY("下表把 ER 图中的每条连线整理成文字，标注基数与关联字段，可直接作为后端实体关联写法的对照清单。")
rel = [
    ["1", "sys_user → sys_user_role", "1:N", "sys_user.id = sys_user_role.user_id", "一个用户可拥有多个角色"],
    ["2", "sys_role → sys_user_role", "1:N", "sys_role.id = sys_user_role.role_id", "一个角色可分配多个用户"],
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
for r in rel:
    r[1] = brk(r[1])
    r[3] = brk(r[3])
TABLE(["序号", "关系（父 → 子）", "基数", "关联条件", "业务含义"], rel,
      [0.9, 4.8, 1.0, 5.5, 3.5],
      [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER,
       WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT], font=8.5, mono_cols=(1, 3))
CAP("表 4-2  实体关系与基数汇总（20 条逻辑外键）")
BODY("以上 20 条关系全部为逻辑外键。部署时若需要物理约束，可按本表逐条补充 ALTER TABLE ... ADD CONSTRAINT 语句；"
     "当前设计选择不建物理外键，是为了让测试数据准备与问卷整体重排更灵活。")

new42 = blocks_after(mark)
anchor = h42._p
for el in new42:
    anchor.addnext(el)
    anchor = el
print("4.2 blocks moved:", len(new42))

# ================================================================== 4.4
mark = tail_mark()
BODY("数据字典按模块、按表逐字段展开。每张表先给出定位信息（表名、中文名、所属模块、建表脚本），"
     "再给出字段清单与索引约束清单。字段清单中「非空」列表示该字段是否允许 NULL，「默认值」列按脚本原样给出；"
     "全部中文说明均取自建表语句的 COMMENT，未作推测性补充。")

HEAD("4.4.1  建表脚本与业务表清单", 3)
BODY("数据字典由脚本自动解析建表语句生成，保证与真实库结构完全一致，避免手工抄写产生的偏差。")
TABLE(["序号", "脚本文件", "包含内容", "表数"],
      [["1", "deploy/sql/01_schema_auth.sql", "认证与权限模块（sys_user 至 sys_user_permission）", "6"],
       ["2", "deploy/sql/02_schema_survey.sql", "问卷管理模块（survey、survey_question、survey_question_option）", "3"],
       ["3", "deploy/sql/03_schema_answer.sql", "答卷与统计模块（answer_sheet、answer_item、survey_draft）", "3"],
       ["4", "deploy/sql/90_cleanup_test_data.sql", "测试数据清理脚本，不含建表语句", "0"]],
      [1.0, 4.9, 7.4, 1.3],
      [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT,
       WD_ALIGN_PARAGRAPH.CENTER], mono_cols=(1,),
      small=[(1, 20, 7.5)])
CAP("表 4-3  数据来源脚本清单")

BODY("全库共 14 张业务表，按功能划分为 4 个模块，各表字段数量与职责如下表所示。")
rows = []
for i, name in enumerate(TABLES, 1):
    m = S[name]
    rows.append([str(i), brk(name), m["cn"], str(len(m["columns"])), m["module"]])
TABLE(["序号", "表名", "中文名称", "字段数", "所属模块"], rows,
      [1.0, 4.6, 4.0, 1.5, 3.2],
      [WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT,
       WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.CENTER], mono_cols=(1,))
CAP("表 4-4  业务表清单（共 14 张）")
for m in MODULES:
    n = len([t for t in TABLES if t and S[t]["module"] == m])
    BULLET("%s（%d 张表）：%s" % (m, n, MODULE_DESC[m]))

tno = 4
for mi, m in enumerate(MODULES, 2):
    HEAD("4.4.%d  %s模块" % (mi, m), 3)
    BODY(MODULE_DESC[m])
    names = [t for t in TABLES if S[t]["module"] == m]
    for ti, name in enumerate(names, 1):
        meta = S[name]
        HEAD4("4.4.%d.%d  %s（%s）" % (mi, ti, name, meta["cn"]))

        loc = doc.add_table(rows=0, cols=4)
        loc.alignment = WD_TABLE_ALIGNMENT.CENTER
        for k1, v1, k2, v2 in [
            ("表名", name, "中文名称", meta["cn"]),
            ("所属模块", m, "字段数量", "%d 个" % len(meta["columns"])),
            ("建表脚本", meta["file"], "索引数量", "%d 个" % len(meta["indexes"])),
        ]:
            cells = loc.add_row().cells
            cell_fmt(cells[0], k1, 9, True, WD_ALIGN_PARAGRAPH.CENTER, fill="F2F2F2")
            cell_fmt(cells[1], v1, 9, False, WD_ALIGN_PARAGRAPH.LEFT, mono=True)
            cell_fmt(cells[2], k2, 9, True, WD_ALIGN_PARAGRAPH.CENTER, fill="F2F2F2")
            cell_fmt(cells[3], v2, 9, False, WD_ALIGN_PARAGRAPH.LEFT, mono=True)
        srs_tblpr(loc)
        set_widths(loc, [2.2, 5.2, 2.2, 5.0])
        P("", size=4, after=2)

        frows = []
        for c in meta["columns"]:
            keys = c["keys"]
            keytxt = " ".join(x for x in ("PK" if "PK" in keys else "",
                                          "FK" if "FK" in keys else "",
                                          "UK" if ("UK" in keys or "UQ*" in keys) else "") if x)
            if c.get("auto_increment"):
                keytxt = (keytxt + " 自增").strip()
            elif "GENERATED" in c["type"].upper():
                keytxt = (keytxt + " 生成列").strip()
            frows.append([brk(c["name"]), brk(c["type"]),
                          "是" if c["not_null"] else "否",
                          "-" if c["default"] in ("", None) else brk(c["default"]),
                          keytxt or "-", c["comment"]])
        tno += 1
        TABLE(["字段名", "数据类型", "非空", "默认值", "键", "说明"], frows,
              [3.0, 2.5, 1.2, 3.2, 1.6, 3.1],
              [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER,
               WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT],
              font=8.5, mono_cols=(0, 1, 3),
              small=[(0, 16, 7.5), (1, 13, 7.5), (3, 15, 7.5)])
        CAP("表 4-%d  %s 字段说明" % (tno, name), before=2, after=8)

        irows = []
        for ix in meta["indexes"]:
            kind = "主键" if ix["primary"] else ("唯一" if ix["unique"] else "普通")
            irows.append([brk(ix["name"]), brk(", ".join(ix["cols"])), kind])
        tno += 1
        TABLE(["索引名", "索引字段", "类型"], irows,
              [4.6, 7.4, 2.4],
              [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER],
              font=8.5, mono_cols=(0, 1))
        CAP("表 4-%d  %s 索引与约束" % (tno, name), before=2, after=10)

HEAD("4.4.6  枚举与取值字典", 3)
BODY("以下枚举值均取自建表脚本的字段注释，是接口参数校验与前端下拉框的唯一依据。")
enum_rows = [
    ["sys_user.status", "TINYINT", "1 正常；0 禁用"],
    ["sys_user.audit_status", "TINYINT", "0 待审核；1 已通过；2 已驳回（管理员导入的账号默认已通过）"],
    ["sys_user.must_change_password", "TINYINT", "1 下次登录强制改密；0 否"],
    ["sys_role.code", "VARCHAR(32)", "ROLE_ADMIN 管理员；ROLE_TEACHER 教师；ROLE_STUDENT 学生"],
    ["sys_role.builtin", "TINYINT", "1 内置角色，不可删除；0 自定义"],
    ["sys_permission.code", "VARCHAR(64)", "「模块:动作[:范围]」，如 survey:create、survey:publish:self"],
    ["sys_login_log.result", "TINYINT", "1 成功；0 失败（失败原因记入 reason 字段）"],
    ["sys_operation_log.result", "TINYINT", "1 成功；0 失败"],
    ["survey.category", "VARCHAR(32)", "TEACHING 教学反馈；ACTIVITY 活动报名；SATISFACTION 满意度；OTHER 其他"],
    ["survey.anonymous", "TINYINT", "1 匿名；0 实名"],
    ["survey.status", "VARCHAR(16)", "DRAFT 草稿；PUBLISHED 已发布；PAUSED 已暂停；CLOSED 已结束"],
    ["survey.limit_once", "TINYINT", "1 每人一份；0 可重复填写"],
    ["survey_question.question_type", "VARCHAR(16)", "SINGLE 单选；MULTIPLE 多选；TEXT 单行文本；TEXTAREA 多行文本；RATING 评分"],
    ["survey_question.is_required", "TINYINT", "1 必答；0 选答"],
    ["answer_sheet.limit_once", "TINYINT", "提交时对 survey.limit_once 的快照，决定是否占用限填槽位"],
    ["answer_sheet.is_valid", "TINYINT", "1 有效（计入统计）；0 已标记无效"],
    ["*.deleted", "TINYINT", "0 正常；1 已删除（逻辑删除，仅 sys_user、survey、answer_sheet 使用）"],
]
for r in enum_rows:
    r[0] = brk(r[0])
tno += 1
TABLE(["字段", "类型", "取值与含义"], enum_rows, [5.0, 2.4, 7.2],
      [WD_ALIGN_PARAGRAPH.LEFT, WD_ALIGN_PARAGRAPH.CENTER, WD_ALIGN_PARAGRAPH.LEFT],
      font=8.5, mono_cols=(0, 1))
CAP("表 4-%d  枚举与取值字典" % tno, before=2, after=10)

HEAD("4.4.7  索引设计与查询场景对照", 3)
BODY("索引不是字段清单的附属品，每一条都对应一个具体查询。下表按「查询场景 → 命中索引」的方式列出全库索引的设计意图，"
     "可在性能优化与慢查询排查时直接对照。")
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
    ["问卷按标题模糊搜", "survey", "idx_title", "前缀匹配可用，注意避免 % 开头"],
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
for r in idx_rows:
    r[1] = brk(r[1])
    r[2] = brk(r[2])
tno += 1
TABLE(["查询场景", "表", "索引", "设计意图"], idx_rows, [3.2, 3.2, 4.4, 3.8],
      [WD_ALIGN_PARAGRAPH.LEFT] * 4, font=8.5, mono_cols=(1, 2))
CAP("表 4-%d  索引设计与查询场景对照" % tno, before=2, after=10)

new44 = blocks_after(mark)
anchor = h44._p
for el in new44:
    anchor.addnext(el)
    anchor = el
print("4.4 blocks moved:", len(new44), " last table no:", tno)

# 打开时提示更新域（重算目录页码）
settings = doc.settings.element
if settings.find(qn("w:updateFields")) is None:
    uf = OxmlElement("w:updateFields")
    uf.set(qn("w:val"), "true")
    settings.append(uf)

doc.save(str(SRC))
print("saved:", SRC, SRC.stat().st_size, "bytes")
print("paragraphs:", len(doc.paragraphs), " tables:", len(doc.tables))

# 顺带把原文 3 张表的列宽按同一规则收进版心（原文件里它们是 15.04 / 14.81cm，超出右边距）
for t in doc.tables:
    grid = t._tbl.find(qn("w:tblGrid"))
    if grid is None:
        continue
    cm = [int(c.get(qn("w:w"))) / 1440 * 2.54 for c in grid.findall(qn("w:gridCol"))]
    if cm and sum(cm) > USABLE + 0.01:
        set_widths(t, cm)
doc.save(str(SRC))
