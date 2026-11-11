"""把 xlsx 转成 JSON，供 smoke_stat.ps1 断言用。

用法: python read_xlsx.py <book.xlsx> <out.json>

为什么不直接打印到 stdout：Windows 控制台代码页（GBK）会把中文
Sheet 名和单元格内容转坏，脚本里读回来全是乱码。写 UTF-8 文件最稳。
"""
import json
import sys
import warnings

warnings.filterwarnings("ignore")
import openpyxl


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: read_xlsx.py <book.xlsx> <out.json>", file=sys.stderr)
        return 2

    wb = openpyxl.load_workbook(sys.argv[1])
    out = {"sheets": wb.sheetnames, "data": {}}
    for name in wb.sheetnames:
        rows = []
        for row in wb[name].iter_rows(values_only=True):
            rows.append(["" if cell is None else str(cell) for cell in row])
        out["data"][name] = rows

    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
