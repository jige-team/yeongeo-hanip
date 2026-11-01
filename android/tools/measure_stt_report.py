"""인식률 측정 결과를 표로 만든다. measure_stt.sh report가 앱 로그를 표준 입력으로 넘긴다.

로그 두 줄을 짝지어 읽는다.
  Speech: 듣기 끝: 입력 크기 최대=1234.5 (60회), 마지막 인식="share your screen"
  Drive : 판정 target="share your screen" success=true
"""
import re
import sys


def words(text):
    text = re.sub(r"['’]", "", text.lower())
    return re.sub(r"[^a-z0-9 ]", " ", text).split()


rows = []
heard = None
for line in sys.stdin:
    m = re.search(r'듣기 끝: 입력 크기 최대=([0-9.]+).*마지막 인식="(.*)"', line)
    if m:
        heard = (float(m.group(1)), m.group(2))
        continue
    m = re.search(r'판정 target="(.*)" success=(true|false)', line)
    if m and heard:
        target, said = m.group(1), heard[1]
        said_words = words(said)
        exact = all(w in said_words for w in words(target))
        rows.append((target, said, heard[0], m.group(2) == "true", exact))
        heard = None

if not rows:
    print("기록이 없습니다. 측정을 마친 뒤 다시 실행하세요.")
    sys.exit()

print("{:<22} {:<30} {:>6}  {}  {}".format("목표", "인식된 말", "입력", "완전일치", "판정"))
for target, said, rms, ok, exact in rows:
    print("{:<22} {:<30} {:>6.0f}  {:^8}  {}".format(target, said[:30], rms, "O" if exact else "-", "성공" if ok else "실패"))

n = len(rows)
ok = sum(1 for r in rows if r[3])
exact = sum(1 for r in rows if r[4])
quiet = sum(1 for r in rows if r[2] < 1000)
print()
print("판정 성공률(지금 규칙): {}/{} ({}%)".format(ok, n, ok * 100 // n))
print("단어 완전 일치:         {}/{} ({}%)  <- 판정을 너그럽게 하기 전 기준에 가깝다".format(exact, n, exact * 100 // n))
print("입력이 작았던 시도(1000 미만): {}번  <- 많으면 마이크 볼륨 문제".format(quiet))
