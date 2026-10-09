"""JaCoCo の XML から、行と分岐のカバレッジを Markdown の表にする。

CI のジョブサマリーに出す。閾値の判定はしない（計測と可視化だけ）。

    python3 .github/scripts/coverage-summary.py <jacocoTestReport.xml>
"""

import sys
import xml.etree.ElementTree as ET

PREFIX = "com/quizapp/"


def ratio(element, kind):
    counter = next((c for c in element.findall("counter") if c.get("type") == kind), None)
    if counter is None:
        return "-"
    missed, covered = int(counter.get("missed")), int(counter.get("covered"))
    return f"{covered * 100 / (missed + covered):.1f}%（{covered}/{missed + covered}）"


def main(path):
    report = ET.parse(path).getroot()
    print("### カバレッジ（quiz-service）")
    print()
    print(f"行 **{ratio(report, 'LINE')}** / 分岐 **{ratio(report, 'BRANCH')}**")
    print()
    print("| パッケージ | 行 | 分岐 |")
    print("| --- | --- | --- |")
    for package in sorted(report.findall("package"), key=lambda p: p.get("name")):
        name = (package.get("name") + "/").removeprefix(PREFIX).rstrip("/").replace("/", ".") or "（直下）"
        print(f"| {name} | {ratio(package, 'LINE')} | {ratio(package, 'BRANCH')} |")


if __name__ == "__main__":
    main(sys.argv[1])
