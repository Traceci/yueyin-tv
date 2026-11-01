# 第三方代码来源声明

本目录下的在线音乐实现，部分移植自下列第三方开源项目。按其许可证要求，保留来源与许可说明。

## lemon-music（柠檬音乐）

- 仓库：https://github.com/jia070310/lemon-muisc
- 移植来源：`server/musicSdk.js` 中**各平台元数据接口**的调用方式与字段解析（搜索、榜单、歌单、新歌新碟）
- **未移植取链逻辑**：本项目的播放地址由使用者自行导入的 LX 协议脚本提供，不在本仓库内实现
- 许可证：MIT License

```
MIT License

Copyright (c) lemon-music contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### 与上游的差异说明

上游项目采用「空壳」设计：搜索、发现、试听、下载**全部**依赖使用者自行导入的兼容音源脚本，仓库内不直接调用任何音乐平台接口。

本项目只移植了其中的**元数据部分**（搜索与发现页的接口调用和字段解析），这部分内置在客户端代码里；播放地址的获取同样交给使用者自行导入的 LX 协议脚本，与上游一致。

因此本项目与上游在暴露面上**并不对等**：上游不含任何平台接口调用，本项目含元数据接口调用。这部分自行实现的部分及其后果，由本项目自行承担，与上游项目及其贡献者无关。

上游项目的作者与贡献者不对本项目的任何行为负责，也不应因本项目的使用而被牵连。
