# bismuth

| ![screenshot0.png](screenshot0.png) | ![screenshot1.png](screenshot1.png) |
|:-----------------------------------:|:------------------------------------|

自动生成壁纸, 下载见 *Releases*

更多介绍: <https://iovxw.net/p/bismuth.html>

## Build

To build this project, you need install [boot](https://github.com/boot-clj/boot) first.

Then:

```
boot build
```

## Run

You can start the electron process using
[electron-prebuilt](https://github.com/mafintosh/electron-prebuilt) or
using a downloaded `Electron.app` package:

```
electron target/
```

## Package

The easiest way to package an electron app is by using
[`electron-packager`](https://github.com/maxogden/electron-packager):

```
electron-packager target/ bismuth --platform=linux --arch=ia32 --version=1.3.4
```
## License

This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or
distribute this software, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any
means.
