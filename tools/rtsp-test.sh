#!/bin/bash
# Δοκιμάζει συνηθισμένες διαδρομές RTSP σε καταγραφικό/κάμερα. Ο κωδικός δεν εμφανίζεται πουθενά.
HOST=${1:?usage: rtsp-test.sh HOST [PORT] [CHANNEL]}; PORT=${2:-554}; CH=${3:-1}
read -rp "Χρήστης [admin]: " U; U=${U:-admin}
read -rsp "Κωδικός: " P; echo
enc(){ python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$1"; }
EU=$(enc "$U"); EP=$(enc "$P")
paths=(
 "user=$EU&password=$EP&channel=$CH&stream=1.sdp?real_stream"
 "user=$EU&password=$EP&channel=$CH&stream=1.sdp"
 "user=${EU}_password=${EP}_channel=${CH}_stream=1.sdp?real_stream"
 "cam/realmonitor?channel=$CH&subtype=1"
 "Streaming/Channels/${CH}02"
 "h264/ch$CH/sub/av_stream"
 "ch$CH/sub"
 "live/ch$((CH-1))"
 "$((CH-1))"
)
for auth in yes no; do
 for p in "${paths[@]}"; do
  if [ $auth = yes ]; then url="rtsp://$EU:$EP@$HOST:$PORT/$p"; else url="rtsp://$HOST:$PORT/$p"; fi
  r=$(timeout 8 ffprobe -v error -rtsp_transport tcp -select_streams v:0 -show_entries stream=codec_name,width,height -of csv=p=0 "$url" 2>&1 | head -1)
  case "$r" in
   *401*|*nauthori*) s="401 λάθος στοιχεία";; *404*|*"Not Found"*) s="404 λάθος διαδρομή";;
   "") s="καμία απάντηση";; *[0-9],[0-9]*) s="✓ ΕΝΤΑΞΕΙ ($r)";; *) s="${r:0:50}";;
  esac
  shown=$(echo "$p" | sed "s/$EP/***/g; s/$EU/USER/g")
  printf "%-4s %-55s %s\n" "$([ $auth = yes ] && echo auth || echo -)" "$shown" "$s"
 done
done
