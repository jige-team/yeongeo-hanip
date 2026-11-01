#!/bin/bash
# 마이크 없이 녹음 파일로 인식 엔진을 시험한다 (디버그 빌드 전용)
#
#   ./tools/probe_stt.sh tts      원어민 합성음(Samantha)으로 표현 20개 파일을 만든다  → 엔진 기준선
#   ./tools/probe_stt.sh record [개수]  내 목소리로 표현을 Mac에서 바로 녹음한다 (기본 20개) → 발음 평가
#   ./tools/probe_stt.sh run      만든 파일을 에뮬레이터 엔진에 넣고 결과 표를 보여 준다
#
# 에뮬레이터 마이크 경로를 건너뛰므로, "엔진·발음 문제"와 "마이크 경로 문제"를 가를 수 있다.

set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/probe_out"          # 만든 PCM 파일 (Git 제외)
PHRASES="$HERE/../app/src/main/assets/phrases.json"
FFMPEG=/opt/homebrew/bin/ffmpeg
APP=app.redlight.hanmadi
mkdir -p "$WORK"

# 업무 10개 + 일상 10개 (항상 같은 20개)
list() {
  /usr/bin/python3 -I -c '
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))
work = [p for p in items if p["set"] == "work"][:10]
daily = [p for p in items if p["set"] == "daily"][:10]
for p in work + daily:
    print(p["id"] + "|" + p["chunk"] + "|" + p["chunkKo"])
' "$PHRASES"
}

to_pcm() { "$FFMPEG" -loglevel error -y -i "$1" -ar 16000 -ac 1 -f s16le "$2"; }

case "$1" in
  tts)
    list | while IFS='|' read -r id chunk ko; do
      say -v Samantha -o "$WORK/tmp.aiff" "$chunk"
      to_pcm "$WORK/tmp.aiff" "$WORK/tts_$id.pcm"
    done
    rm -f "$WORK/tmp.aiff"
    echo "합성음 20개를 만들었습니다: $WORK"
    ;;
  record)
    COUNT=${2:-20}
    echo "표현 ${COUNT}개를 하나씩 녹음합니다. '띵' 소리가 나면 4초 안에 말하세요. (Ctrl+C로 중단)"
    echo "처음 한 번은 터미널의 마이크 권한을 허용해야 할 수 있습니다."
    read -r -p "준비되면 Enter를 누르세요." _ < /dev/tty
    list | head -n "$COUNT" | while IFS='|' read -r id chunk ko; do
      echo
      echo "▶ $chunk  ($ko)"
      say -v Samantha "$chunk"
      # 녹음을 먼저 켜고 띵을 울린다. 띵 뒤에 녹음을 켜면 켜지는 동안 앞부분이 빠졌다(2026-11-01)
      # [0] = MacBook Air 마이크 (ffmpeg -f avfoundation -list_devices true -i "" 로 확인)
      "$FFMPEG" -loglevel error -y -f avfoundation -i ":0" -t 5 -ar 16000 -ac 1 -f s16le "$WORK/user_$id.pcm" < /dev/null &
      sleep 0.8
      afplay /System/Library/Sounds/Ping.aiff
      wait
      echo "  녹음됨"
    done
    echo
    echo "녹음 끝. 이제: $0 run"
    ;;
  run)
    # targets.txt: "파일명|목표 표현"
    : > "$WORK/targets.txt"
    list | while IFS='|' read -r id chunk ko; do
      for kind in tts user; do
        if [ -f "$WORK/${kind}_$id.pcm" ]; then echo "${kind}_$id.pcm|$chunk" >> "$WORK/targets.txt"; fi
      done
    done
    count=$(wc -l < "$WORK/targets.txt" | tr -d ' ')
    if [ "$count" -eq 0 ]; then echo "파일이 없습니다. 먼저 tts 또는 record를 실행하세요."; exit 1; fi
    # 엔진에 넣기 전에 다듬는다:
    # - 끝에 무음 1.5초를 붙인다. 붙이지 않으면 "입력 끝"을 알리는 순간 아직 처리 못 한 끝 단어가 버려졌다
    #   (2026-11-01: 합성음 "share your screen" → "share your")
    # - 소리 크기를 파일마다 같은 수준(최대 -3dB)으로 맞춘다
    PROC="$WORK/proc"; rm -rf "$PROC"; mkdir -p "$PROC"
    for f in "$WORK"/*.pcm; do
      name=$(basename "$f")
      # 소리 크기는 최대치가 -3dB가 되게 맞춘다(작으면 키우고, 크면 줄인다). 일정 배율로 키우면 큰 녹음이 찌그러졌다
      peak=$("$FFMPEG" -hide_banner -f s16le -ar 16000 -ac 1 -i "$f" -af volumedetect -f null - 2>&1 | sed -n 's/.*max_volume: \(-*[0-9.]*\) dB.*/\1/p')
      gain=$(/usr/bin/python3 -c "print(round(-3 - float('${peak:-0}'), 1))")
      "$FFMPEG" -loglevel error -y -f s16le -ar 16000 -ac 1 -i "$f" -af "volume=${gain}dB,apad=pad_dur=1.5" -f s16le -ar 16000 -ac 1 "$PROC/$name"
    done
    cp "$WORK/targets.txt" "$PROC/"
    adb shell "run-as $APP --user 10 mkdir -p files/probe"
    for f in "$PROC"/*; do
      adb shell "run-as $APP --user 10 sh -c 'cat > files/probe/$(basename "$f")'" < "$f"
    done
    adb logcat -c
    adb shell am start -n $APP/.presentation.MainActivity --ez probe true > /dev/null
    echo "파일 ${count}개를 엔진에 넣는 중..."
    until adb logcat -d -s Probe:D | grep -q "파일 인식 끝"; do sleep 2; done
    adb logcat -d -s Probe:D | /usr/bin/python3 -I -c '
import re, sys
rows = []
for line in sys.stdin:
    m = re.search(r"file=\"(\w+)_(\w+)\.pcm\" target=\"(.*)\" heard=\"(.*)\" success=(true|false)", line)
    if m:
        rows.append((m.group(1), m.group(3), m.group(4), m.group(5) == "true"))
for kind, label in (("tts", "원어민 합성음 (엔진 기준선)"), ("user", "내 목소리 (발음 평가)")):
    part = [r for r in rows if r[0] == kind]
    if not part:
        continue
    print()
    print("== " + label)
    print("{:<22} {:<30} {}".format("목표", "인식된 말", "판정"))
    for _, target, heard, ok in part:
        print("{:<22} {:<30} {}".format(target, heard[:30], "성공" if ok else "실패"))
    ok = sum(1 for r in part if r[3])
    print("성공 {}/{} ({}%)".format(ok, len(part), ok * 100 // len(part)))
'
    ;;
  *)
    echo "쓰는 법: $0 tts | record | run"
    ;;
esac
