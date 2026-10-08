#!/usr/bin/env python3
"""生成 P2-1 真实文件回放 fixtures。

仅使用标准库，向 bank_forecast/backend/src/test/resources/replay/ 写入
六家银行仿真导出文件，覆盖：列名变体、跨页表头、空行、日期格式、
多币种和异常编码（GB18030 无 BOM、UTF-16LE BOM）。文件同时可用于
后端自动化回放测试和页面上传手工回放。

用法：python3 generate_replay_fixtures.py
"""

import os
import zipfile
from datetime import date
from xml.sax.saxutils import escape

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(BASE_DIR, "..", "backend", "src", "test", "resources", "replay")

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>
"""

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>
"""

WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="{sheet_name}" sheetId="1" r:id="rId1"/></sheets>
</workbook>
"""

WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>
"""


def column_letter(index):
    result = ""
    index += 1
    while index > 0:
        index, remainder = divmod(index - 1, 26)
        result = chr(ord("A") + remainder) + result
    return result


def excel_serial(year, month, day):
    return (date(year, month, day) - date(1899, 12, 30)).days


def sheet_xml(rows):
    """rows: 列表，每行是单元格列表；None 表示空单元格，int/float 为数值，其余为内联字符串。"""
    parts = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for row_no, row in enumerate(rows, start=1):
        if not row:
            parts.append('<row r="%d"></row>' % row_no)
            continue
        cells = []
        for col, value in enumerate(row):
            if value is None:
                continue
            ref = "%s%d" % (column_letter(col), row_no)
            if isinstance(value, (int, float)):
                cells.append('<c r="%s"><v>%s</v></c>' % (ref, value))
            else:
                cells.append('<c r="%s" t="inlineStr"><is><t>%s</t></is></c>' % (ref, escape(str(value))))
        parts.append('<row r="%d">%s</row>' % (row_no, "".join(cells)))
    parts.append('</sheetData></worksheet>')
    return "".join(parts)


def write_xlsx(filename, sheet_name, rows):
    path = os.path.join(OUT_DIR, filename)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", CONTENT_TYPES)
        zf.writestr("_rels/.rels", ROOT_RELS)
        zf.writestr("xl/workbook.xml", WORKBOOK.format(sheet_name=escape(sheet_name)))
        zf.writestr("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zf.writestr("xl/worksheets/sheet1.xml", sheet_xml(rows))
    print("written", path)


def write_csv(filename, text, encoding, bom=b""):
    path = os.path.join(OUT_DIR, filename)
    with open(path, "wb") as fh:
        fh.write(bom + text.encode(encoding))
    print("written", path)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # 中国银行：列名变体（借方发生额/贷方发生额）
    write_xlsx("replay-boc-column-variants.xlsx", "中国银行", [
        ["中国银行企业网银交易明细"],
        ["交易日期", "交易流水号", "借方发生额", "贷方发生额", "余额", "对方户名", "摘要"],
        ["2026/09/01", "BOC-RP-001", "50000.00", None, "950000.00", "供应商甲", "货款支付"],
        ["2026/09/02", "BOC-RP-002", None, "120000.00", "1070000.00", "客户乙", "项目回款"],
        ["2026/09/03", "BOC-RP-003", "8000.00", None, "1062000.00", "供应商丙", "服务费"],
    ])

    # 工商银行：日期格式（Excel 序列值、日期时间字符串、斜杠日期）+ 列名变体（记账日期/对方名称/用途）
    write_xlsx("replay-icbc-date-formats.xlsx", "工商银行", [
        ["记账日期", "流水号", "借方金额", "贷方金额", "对方名称", "用途"],
        [excel_serial(2026, 9, 5), "ICBC-RP-001", "3000.00", None, "供应商丁", "采购付款"],
        ["2026-09-06 10:30:00", "ICBC-RP-002", None, "45000.00", "客户戊", "销售回款"],
        ["2026/09/07", "ICBC-RP-003", "1200.50", None, "供应商己", "运费"],
    ])

    # 广发银行：多币种（USD/CNY/HKD 币种列）
    write_xlsx("replay-cgb-multi-currency.xlsx", "广发银行", [
        ["交易日", "凭证号", "支出金额", "收入金额", "币种", "对手方", "交易用途"],
        ["2026-09-01", "CGB-RP-001", "1000.00", None, "USD", "境外供应商", "进口货款"],
        ["2026-09-02", "CGB-RP-002", None, "25000.00", "CNY", "客户庚", "货款"],
        ["2026-09-03", "CGB-RP-003", None, "8000.00", "HKD", "客户辛", "服务费"],
    ])

    # 平安银行：跨页表头（中部重复表头）+ 空行 + 合计页脚 + 括号列名变体
    write_xlsx("replay-pab-paged-header.xlsx", "平安银行", [
        ["平安银行账户交易明细清单"],
        ["账户：1100****1234  币种：人民币"],
        ["日期", "交易流水号", "借方金额（支出）", "贷方金额（收入）", "交易后余额", "对方户名", "附言"],
        ["2026-09-01", "PAB-RP-001", "20000.00", None, "480000.00", "供应商壬", "货款"],
        ["2026-09-02", "PAB-RP-002", None, "60000.00", "540000.00", "客户癸", "回款"],
        [],
        ["日期", "交易流水号", "借方金额（支出）", "贷方金额（收入）", "交易后余额", "对方户名", "附言"],
        ["2026-09-03", "PAB-RP-003", "5000.00", None, "535000.00", "供应商子", "办公费"],
        ["2026-09-04", "PAB-RP-004", None, "15000.00", "550000.00", "客户丑", "服务费"],
        ["合计", None, "25000.00", "75000.00", None, None, None],
    ])

    # 上海银行：日期格式（点分日期、中文日期）
    write_xlsx("replay-bosc-date-formats.xlsx", "上海银行", [
        ["交易日期", "交易流水号", "借方金额", "贷方金额", "余额", "对方户名", "摘要"],
        ["2026.09.08", "BOSC-RP-001", "300.00", None, "100000.00", "供应商寅", "杂费"],
        ["2026年9月9日", "BOSC-RP-002", None, "9000.00", "109000.00", "客户卯", "回款"],
    ])

    # 苏州银行：标准列 + 空行
    write_xlsx("replay-suzb-standard.xlsx", "苏州银行", [
        ["交易日期", "凭证号", "借方金额", "贷方金额", "交易后余额", "对方户名", "摘要"],
        ["2026-09-10", "SUZB-RP-001", "700.00", None, "50300.00", "供应商辰", "货款"],
        [],
        ["2026-09-11", "SUZB-RP-002", None, "1300.00", "51600.00", "客户巳", "回款"],
    ])

    # 中国银行 CSV：GB18030 无 BOM 异常编码 + 借/贷方向 + 币种列 + 中部重复表头
    write_csv(
        "replay-boc-gb18030.csv",
        "交易流水号,交易日期,借贷标志,金额,币种,对方户名,摘要\n"
        "BOC-CSV-RP-001,20260901,借,1000.00,CNY,供应商甲,货款\n"
        "BOC-CSV-RP-002,2026/09/02,贷,2500.50,USD,客户乙,回款\n"
        "交易流水号,交易日期,借贷标志,金额,币种,对方户名,摘要\n"
        "BOC-CSV-RP-003,2026-09-03,借,300.00,CNY,供应商丙,服务费\n",
        "gb18030",
    )

    # 工商银行 CSV：UTF-16LE BOM + 制表符分隔 + 日期时间与点分日期 + 币种列
    write_csv(
        "replay-icbc-utf16le.csv",
        "交易流水号\t交易日期\t收支方向\t交易金额\t币种\t对方名称\t用途\n"
        "ICBC-CSV-RP-001\t2026-09-05 09:15:30\t收入\t8800.00\tCNY\t客户午\t服务费回款\n"
        "ICBC-CSV-RP-002\t2026.09.06\t支出\t1500.00\tHKD\t供应商未\t采购\n",
        "utf-16-le",
        bom=b"\xff\xfe",
    )


if __name__ == "__main__":
    main()
