# Third-Party Notices

本项目（AE2 Infinite Expansion）的部分设计灵感与实现思路参考了以下开源项目。
所有代码均为原创重写，未直接复制其源码。

## OmniSequence: Transfinite

- **作者**: HibikiShino
- **许可证**: MIT License
- **版权**: Copyright (c) 2026 HibikiShino and OmniSequence: Transfinite contributors
- **来源**: 见作者发布渠道（CurseForge / Modrinth / GitHub）

**参考内容**（均为思路借鉴，代码原创实现）：

1. **虚拟 CPU lane（量子分裂）** —— `IntegratedCPUBE.createVirtualCpu()` 的
   多 lane 并行调度思路，参考 Omni 的 Omni-Computation Core。
2. **时间片限流** —— `CraftingCpuLogicMixin` 的 tick 预算机制
   （32ms/tick 上限，防止高线程数导致服务器卡顿），参考 Omni 的调度策略。
3. **CPU 选择列表显示** —— `CPUSelectionListMixin` 的 ∞ 显示思路，
   参考 Omni 的 CPUSelectionListMixin。

### MIT License (OmniSequence: Transfinite)

```
MIT License

Copyright (c) 2026 HibikiShino and OmniSequence: Transfinite contributors

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
