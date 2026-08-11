# Third-Party Notices

本模组（ae2-addon）的部分设计与代码参考、改编自以下开源项目：

## OmniSequence-Transfinite

- 项目地址: https://github.com/AyaYumi/OmniSequence-Transfinite
- 许可证: MIT License, Copyright (c) 2026 HibikiShino
- 参考/改编的具体组件（非完整清单）:
  - 时间片限流调度思想（CraftingCpuLogic 循环预算控制）
  - 批量推送 / 运行时缩放配方（ScaledPattern，改编自 MolecularScaledPattern）
  - 虚拟 CPU lane（量子分裂线程）设计（CraftingCPUClusterAccessor 等）
  - AE2 菜单 opener 注册机制（MenuOpener.addOpener + NetworkHooks locator 协议）
  - CPU 列表 / 合成确认界面的 ∞ 显示思路（CPUSelectionListMixin 等）

### MIT License 全文

```
MIT License

Copyright (c) 2026 HibikiShino

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

---

本模组还与以下 Minecraft 模组集成（不捆绑其二进制/资源）:
- Applied Energistics 2, version 15.4.10 (LGPL-3.0)
- ExtendedAE / ExtendedAE Plus / JEI 等（按其各自许可证使用）
