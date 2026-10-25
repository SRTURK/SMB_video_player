from pathlib import Path
import shutil, subprocess
root=Path(__file__).resolve().parent/'fixtures'
season=root/'Season1'
season.mkdir(parents=True,exist_ok=True)
video=season/'第2集 # 100%.mp4'
subprocess.run(['ffmpeg','-y','-f','lavfi','-i','testsrc2=size=640x360:rate=30','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','20','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac',str(video)],check=True)
shutil.copyfile(video,season/'第10集 - 山野与远行 长文件名显示两行后应自动省略.mp4')
(season/'Extras').mkdir(exist_ok=True)
(season/'subtitle.srt').write_text('Synthetic subtitle fixture',encoding='utf-8')
for i in range(35):(root/'Scroll'/f'Folder{i:02}').mkdir(parents=True,exist_ok=True)
