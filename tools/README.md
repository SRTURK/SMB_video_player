# 可复用的真机测试

需要 Python、FFmpeg、ADB，以及 JDK/Android SDK。

1. 在虚拟环境安装 `pip install -r tools/requirements-test.txt`。
2. 运行 `python tools/create_test_fixtures.py` 生成合成视频与滚动测试目录（均被 Git 忽略）。
3. 运行 `python tools/smb_test_server.py`，仅监听 `127.0.0.1:1445`，共享只读。
4. `adb reverse tcp:1445 tcp:1445`。
5. `gradlew -PdeviceLab :app:assembleLab :app:assembleLabAndroidTest`，安装 lab 和 androidTest APK。
6. 执行：

```text
adb shell am instrument -w -e smbTestFile "smb://127.0.0.1:1445/fixture/Season1/%E7%AC%AC2%E9%9B%86%20%23%20100%25.mp4" cn.lanvideo.app.lab.test/androidx.test.runner.AndroidJUnitRunner
```

额外片源测试可由环境变量 `SMB_TEST_ACTUAL_DIR` 指向操作者自备目录，并传入 `-e actualSmbFile smb://127.0.0.1:1445/actual/...`。未传入时该项跳过。测试包使用独立应用 ID，不清除正常应用数据。结束后停止测试服务、删除 ADB reverse、卸载 lab 和 lab.test 包。
