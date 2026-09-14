#!/usr/bin/env python3
"""构建后自检：找出 reobf 撞名导致的「引用方被改名、声明方没改名」不一致。

背景（2026-09-14 崩溃）：`FeederHost.isRemoved()` 与 MC `Entity.isRemoved()` 撞名，
ForgeGradle 的 reobf 把**调用点**和**实现类**重命名成了 SRG `m_58901_`，
但**接口自己的抽象声明**没被改名 → 运行时 `NoSuchMethodError`，
只有真正打开那个 GUI 才会炸（编译、构建全过）。

本脚本扫描 jar 内 `com/ae2addon/**` 的所有类：
  凡是「被引用的成员名是 SRG 形式（m_数字_ / f_数字_）」、且被引用类的**直接父类是 java/lang/Object**
  （即它必须自己声明该成员，不可能是从 MC 基类继承）的引用，
  检查该类里是否真的声明了同名成员 —— 没有就是不一致（迟早 NoSuchMethodError）。
  直接父类不是 Object 的（我们继承 MC/AE2 的类），合法继承成员无法从 mod jar 证伪，跳过。

用法：python3 tools/check_reobf_consistency.py build/libs/ae2-addon.jar
退出码 0 = 干净；1 = 有不一致。
"""
import re
import struct
import sys
import zipfile

SRG = re.compile(r"^(m|f)_\d+_")
OWN_PREFIX = "com/ae2addon/"


def parse_class(data):
    """返回 (cp, 声明的方法名集合, 声明的字段名集合)。cp: index -> (tag, a, b)"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    cp_count = int.from_bytes(data[8:10], "big")
    cp = {}
    i = 10
    k = 1
    while k < cp_count:
        tag = data[i]
        if tag == 1:
            ln = int.from_bytes(data[i + 1:i + 3], "big")
            cp[k] = (1, data[i + 3:i + 3 + ln].decode("utf-8", "replace"))
            i += 3 + ln
        elif tag in (7, 8, 16, 19, 20):
            cp[k] = (tag, int.from_bytes(data[i + 1:i + 3], "big"))
            i += 3
        elif tag == 15:
            cp[k] = (tag, data[i + 1], int.from_bytes(data[i + 2:i + 4], "big"))
            i += 4
        elif tag in (3, 4):
            cp[k] = (tag, int.from_bytes(data[i + 1:i + 5], "big"))
            i += 5
        elif tag in (5, 6):
            cp[k] = (tag, int.from_bytes(data[i + 1:i + 9], "big"))
            i += 9
            k += 1
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[k] = (tag, int.from_bytes(data[i + 1:i + 3], "big"),
                     int.from_bytes(data[i + 3:i + 5], "big"))
            i += 5
        else:
            raise ValueError(f"unknown cp tag {tag} at {i}")
        k += 1

    def utf(idx):
        entry = cp.get(idx)
        return entry[1] if entry and entry[0] == 1 else None

    def clsname(idx):
        entry = cp.get(idx)
        if entry and entry[0] == 7:
            return utf(entry[1])
        return None

    # 跳过 cp，读成员表
    j = i
    j += 2  # access
    this_idx = int.from_bytes(data[j:j + 2], "big")
    super_idx = int.from_bytes(data[j + 2:j + 4], "big")
    this_name = clsname(this_idx)
    super_name = clsname(super_idx)
    j += 4
    iface_count = int.from_bytes(data[j:j + 2], "big")
    j += 2 + iface_count * 2

    def read_members():
        nonlocal j
        count = int.from_bytes(data[j:j + 2], "big")
        j += 2
        names = set()
        for _ in range(count):
            name_idx = int.from_bytes(data[j + 2:j + 4], "big")
            names.add(utf(name_idx))
            attr_count = int.from_bytes(data[j + 6:j + 8], "big")
            j += 8
            for _ in range(attr_count):
                ln = int.from_bytes(data[j + 2:j + 6], "big")
                j += 6 + ln
        return names

    fields = read_members()
    methods = read_members()
    return cp, methods, fields, super_name


def main(path):
    z = zipfile.ZipFile(path)
    parsed = {}
    for name in z.namelist():
        if name.endswith(".class"):
            try:
                parsed[name] = parse_class(z.read(name))
            except Exception:
                parsed[name] = None

    violations = []
    for name, info in parsed.items():
        if info is None:
            continue
        cp, methods, fields, _super = info

        def utf(idx):
            entry = cp.get(idx)
            return entry[1] if entry and entry[0] == 1 else None

        def owner(idx):
            entry = cp.get(idx)
            if entry and entry[0] == 7:
                return utf(entry[1])
            return None

        for entry in cp.values():
            tag = entry[0]
            if tag not in (9, 10, 11):  # Fieldref / Methodref / InterfaceMethodref
                continue
            owner_name = owner(entry[1])
            nat = cp.get(entry[2])
            if not owner_name or not nat or nat[0] != 12:
                continue
            member = utf(nat[1])
            if not member or not SRG.match(member):
                continue
            if not owner_name.startswith(OWN_PREFIX):
                continue  # MC 自己的 SRG 引用正常
            target = owner_name + ".class"
            tinfo = parsed.get(target)
            if tinfo is None:
                continue
            _, tmethods, tfields, tsuper = tinfo
            # 只有「直接父类是 java/lang/Object」的自家类才能确证成员必须自己声明；
            # 继承了 MC/AE2 基类的类，m_xxx_ 往往是合法继承成员（无法从 mod jar 证伪）。
            if tsuper != "java/lang/Object":
                continue
            if member not in tmethods and member not in tfields:
                violations.append((name, owner_name, member))

    if violations:
        print("❌ 发现 reobf 不一致（引用方用了 SRG 名，声明方没有）：")
        for src, owner_name, member in sorted(set(violations)):
            print(f"   {src}  →  {owner_name}.{member}")
        return 1
    print("✅ reobf 一致性检查通过（无 SRG 撞名残留）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "build/libs/ae2-addon.jar"))
