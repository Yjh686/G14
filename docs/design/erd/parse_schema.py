# -*- coding: utf-8 -*-
"""从 deploy/sql/*.sql 解析出表结构，输出 schema.json（供 ER 图与数据字典生成使用）。"""
import json
import re
from pathlib import Path

SQL_DIR = Path(r"D:\G14(1)\deploy\sql")
OUT = Path(r"D:\G14(1)\docs\design\erd\schema.json")

FILES = ["01_schema_auth.sql", "02_schema_survey.sql", "03_schema_answer.sql"]

TABLE_CN = {
    "sys_user": "用户表",
    "sys_role": "角色表",
    "sys_permission": "权限字典表",
    "sys_user_role": "用户-角色关联表",
    "sys_role_permission": "角色-权限关联表",
    "sys_user_permission": "用户追加权限表",
    "sys_login_log": "登录日志表",
    "sys_operation_log": "操作日志表",
    "survey": "问卷主表",
    "survey_question": "问卷题目表",
    "survey_question_option": "问卷选项表",
    "answer_sheet": "答卷头表",
    "answer_item": "答案明细表",
    "survey_draft": "填写草稿表",
}

MODULE = {
    "sys_user": "认证与权限",
    "sys_role": "认证与权限",
    "sys_permission": "认证与权限",
    "sys_user_role": "认证与权限",
    "sys_role_permission": "认证与权限",
    "sys_user_permission": "认证与权限",
    "sys_login_log": "日志审计",
    "sys_operation_log": "日志审计",
    "survey": "问卷管理",
    "survey_question": "问卷管理",
    "survey_question_option": "问卷管理",
    "answer_sheet": "答卷与统计",
    "answer_item": "答卷与统计",
    "survey_draft": "答卷与统计",
}

# 外键关系（逻辑外键，SQL 未建物理约束，由服务层保证）
FK = {
    ("sys_user_role", "user_id"): ("sys_user", "id"),
    ("sys_user_role", "role_id"): ("sys_role", "id"),
    ("sys_role_permission", "role_id"): ("sys_role", "id"),
    ("sys_role_permission", "perm_id"): ("sys_permission", "id"),
    ("sys_user_permission", "user_id"): ("sys_user", "id"),
    ("sys_user_permission", "perm_id"): ("sys_permission", "id"),
    ("sys_user_permission", "grant_by"): ("sys_user", "id"),
    ("sys_login_log", "user_id"): ("sys_user", "id"),
    ("sys_operation_log", "user_id"): ("sys_user", "id"),
    ("survey", "create_by"): ("sys_user", "id"),
    ("survey_question", "survey_id"): ("survey", "id"),
    ("survey_question_option", "question_id"): ("survey_question", "id"),
    ("survey_question_option", "survey_id"): ("survey", "id"),
    ("answer_sheet", "survey_id"): ("survey", "id"),
    ("answer_sheet", "user_id"): ("sys_user", "id"),
    ("answer_item", "sheet_id"): ("answer_sheet", "id"),
    ("answer_item", "survey_id"): ("survey", "id"),
    ("answer_item", "question_id"): ("survey_question", "id"),
    ("survey_draft", "survey_id"): ("survey", "id"),
    ("survey_draft", "user_id"): ("sys_user", "id"),
}

CREATE_RE = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\n\) ENGINE", re.S)
COL_RE = re.compile(r"^\s*`(\w+)`\s+(.+?),?$")
KEY_RE = re.compile(r"^\s*(PRIMARY KEY|UNIQUE KEY `(\w+)`|KEY `(\w+)`)\s*\((.*?)\)", re.M)


def split_comment(rest):
    """把 'VARCHAR(64) NOT NULL DEFAULT 1 COMMENT \\'xxx\\'' 拆成 (类型, NOT NULL?, 默认值, 注释)。"""
    comment = ""
    m = re.search(r"\s*COMMENT\s+'(.*)'\s*,?\s*$", rest, re.S)
    if m:
        comment = m.group(1)
        rest = rest[: m.start()]
    rest = rest.strip().rstrip(",").strip()
    body = rest
    not_null = "NOT NULL" in body
    body = body.replace("NOT NULL", " ")
    auto_inc = "AUTO_INCREMENT" in body
    body = body.replace("AUTO_INCREMENT", " ")
    default = ""
    dm = re.search(r"\bDEFAULT\s+(NULL|CURRENT_TIMESTAMP(?:\s+ON\s+UPDATE\s+CURRENT_TIMESTAMP)?|'(?:[^']*)'|[-\w.]+)", body, re.I)
    if dm:
        val = dm.group(1).strip()
        if val.upper() == "NULL":
            default = "NULL"
        else:
            default = val
        body = body[: dm.start()] + body[dm.end():]
    if "ON UPDATE CURRENT_TIMESTAMP" in body.upper() and not default.startswith("CURRENT_TIMESTAMP"):
        pass
    body = re.sub(r"GENERATED ALWAYS AS\s*\(.*?\)\s*STORED", "GENERATED ALWAYS AS (...) STORED", body, flags=re.S)
    body = re.sub(r"\s+", " ", body).strip()
    return body, not_null, default, comment, auto_inc


def parse():
    tables = {}
    for fn in FILES:
        text = (SQL_DIR / fn).read_text(encoding="utf-8")
        for tm in CREATE_RE.finditer(text):
            name = tm.group(1)
            block = tm.group(2)
            # 生成列（GENERATED ALWAYS AS）在 SQL 里跨多行书写，先折叠成一行再逐行解析
            block = re.sub(
                r"`(\w+)`\s+BIGINT UNSIGNED GENERATED ALWAYS AS \(\s*(.*?)\s*\) STORED(\s+COMMENT '(.*?)')?",
                lambda m: "`%s` BIGINT UNSIGNED GENERATED ALWAYS AS (%s) STORED%s"
                % (
                    m.group(1),
                    re.sub(r"\s+", " ", m.group(2)),
                    " COMMENT '%s'" % m.group(4) if m.group(4) else "",
                ),
                block,
                flags=re.S,
            )
            cols = []
            for line in block.split("\n"):
                lm = COL_RE.match(line)
                if not lm:
                    continue
                col, rest = lm.group(1), lm.group(2)
                typ, not_null, default, comment, auto_inc = split_comment(rest)
                cols.append(
                    {
                        "name": col,
                        "type": typ,
                        "not_null": not_null,
                        "default": default,
                        "comment": comment,
                        "auto_increment": auto_inc,
                    }
                )
            indexes = []
            for km in KEY_RE.finditer(block):
                kind = km.group(1)
                iname = km.group(2) or km.group(3) or "PRIMARY"
                icols = [c.strip().strip("`") for c in km.group(4).split(",")]
                if kind.startswith("PRIMARY"):
                    indexes.append({"name": "PRIMARY", "unique": True, "primary": True, "cols": icols})
                elif kind.startswith("UNIQUE"):
                    indexes.append({"name": iname, "unique": True, "primary": False, "cols": icols})
                else:
                    indexes.append({"name": iname, "unique": False, "primary": False, "cols": icols})
            tables[name] = {
                "name": name,
                "cn": TABLE_CN[name],
                "module": MODULE[name],
                "columns": cols,
                "indexes": indexes,
                "file": fn,
            }
    # 标注键角色
    for tname, t in tables.items():
        pk = {c for idx in t["indexes"] if idx["primary"] for c in idx["cols"]}
        uk_cols = set()
        single_uk = set()
        for idx in t["indexes"]:
            if idx["unique"] and not idx["primary"]:
                uk_cols.update(idx["cols"])
                if len(idx["cols"]) == 1:
                    single_uk.add(idx["cols"][0])
        for c in t["columns"]:
            keys = []
            if c["name"] in pk:
                keys.append("PK")
            if (tname, c["name"]) in FK:
                keys.append("FK")
            if c["name"] in single_uk:
                keys.append("UK")
            elif c["name"] in uk_cols:
                keys.append("UQ*")
            c["keys"] = keys
            if (tname, c["name"]) in FK:
                c["ref"] = "%s.%s" % FK[(tname, c["name"])]
    return tables


def main():
    tables = parse()
    print("表数量:", len(tables))
    for name, t in tables.items():
        print(f"{name:26s} {len(t['columns']):2d} 列  {len(t['indexes'])} 索引")
        for c in t["columns"]:
            if not c["comment"]:
                print("   !! 缺少注释:", name, c["name"])
    OUT.write_text(json.dumps(tables, ensure_ascii=False, indent=1), encoding="utf-8")
    print("written", OUT)


if __name__ == "__main__":
    main()
