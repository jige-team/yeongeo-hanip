#!/bin/bash
# 음성 인식률 측정 (디버그 빌드 전용)
#
# 쓰는 법
#   1) 이어폰을 끼고, Mac 입력 볼륨을 70 정도로 올린다. 에뮬레이터 콘솔에서 P단으로 둔다.
#   2) ./tools/measure_stt.sh start
#        → 앱이 짧은 표현 20개(업무 10 + 일상 10)를 하나씩 들려준다.
#          "띵" 소리가 나면 그 표현을 말한다. 20개에 5분쯤 걸린다.
#   3) 화면 위쪽이 "측정 20/20"을 지나 사라지면: ./tools/measure_stt.sh report
#
# 로그 출처: Speech(인식 결과·입력 크기), Drive(판정)

case "$1" in
  start)
    adb logcat -c
    adb shell am start -n app.redlight.hanmadi/.presentation.MainActivity --ez measure true > /dev/null
    echo "측정 시작. \"띵\" 소리가 나면 표현을 말하세요. 끝나면: $0 report"
    ;;
  report)
    adb logcat -d -s Speech:D Drive:D | /usr/bin/python3 -I "$(dirname "$0")/measure_stt_report.py"
    ;;
  *)
    echo "쓰는 법: $0 start | report"
    ;;
esac
