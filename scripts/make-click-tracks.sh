#!/usr/bin/env bash
# Writes click tracks for checking the decoder and, later, calibration: six seconds at 44.1 kHz, one second of silence,
# then a 10 ms, 1 kHz tone burst every 500 ms (amplitude 0.8, starting at phase 0), so the first click starts at
# exactly 1000 ms of the source. One file per container and MP3 header class, from the same PCM:
#
#   click.wav click-48k.wav click.flac click.ogg click.opus click.m4a
#   click-lame.mp3 (CBR, Info header with a LAME extension and delay), click-vbr.mp3 (Xing with LAME),
#   click-noxing.mp3 (no Xing or Info header)
#
#   scripts/make-click-tracks.sh OUT_DIR
#
# Needs ffmpeg with libmp3lame; libvorbis and libopus are used when present.
set -euo pipefail
OUT=${1:?usage: scripts/make-click-tracks.sh OUT_DIR}
mkdir -p "$OUT"
CLICK="if(gte(t,1)*lt(mod(t-1,0.5),0.01),0.8*sin(2*PI*1000*(t-1)),0)"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i "aevalsrc='${CLICK}|${CLICK}':s=44100:d=6" -c:a pcm_s16le "$OUT/click.wav"
ff -f lavfi -i "aevalsrc='${CLICK}|${CLICK}':s=48000:d=6" -c:a pcm_s16le "$OUT/click-48k.wav"
ff -i "$OUT/click.wav" -c:a flac "$OUT/click.flac"
ff -i "$OUT/click.wav" -c:a aac -b:a 192k "$OUT/click.m4a"
ff -i "$OUT/click.wav" -c:a libmp3lame -b:a 192k "$OUT/click-lame.mp3"
ff -i "$OUT/click.wav" -c:a libmp3lame -q:a 2 "$OUT/click-vbr.mp3"
ff -i "$OUT/click.wav" -c:a libmp3lame -b:a 192k -write_xing 0 "$OUT/click-noxing.mp3"
if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis; then
  ff -i "$OUT/click.wav" -c:a libvorbis -q:a 6 "$OUT/click.ogg"
else
  ff -i "$OUT/click.wav" -c:a vorbis -strict -2 -ac 2 "$OUT/click.ogg"
fi
if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libopus; then
  ff -i "$OUT/click-48k.wav" -c:a libopus -b:a 128k "$OUT/click.opus"
fi
ls -l "$OUT"
