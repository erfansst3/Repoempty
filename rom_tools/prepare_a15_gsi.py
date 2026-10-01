#!/usr/bin/env python3
"""
Vmers - Android 15 GSI preparation and .7z rootfs packaging.

Input:
  raw or Android sparse ext4 system.img

Output:
  vmers_a15_arm64.7z

The final archive intentionally contains a top-level "rootfs/" directory,
matching the Vmers ROM layout.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path


SPARSE_MAGIC = bytes.fromhex("3aff26ed")
EXT4_MAGIC = bytes.fromhex("53ef")


def log(msg: str) -> None:
    print(f"[Vmers-ROM] {msg}", flush=True)


def run(cmd, *, cwd=None) -> None:
    log("$ " + " ".join(map(str, cmd)))
    subprocess.run(cmd, cwd=cwd, check=True)


def need(binary: str) -> None:
    if shutil.which(binary) is None:
        raise SystemExit(f"Missing required tool: {binary}")


def read_magic(path: Path, offset: int, size: int) -> bytes:
    with path.open("rb") as f:
        f.seek(offset)
        return f.read(size)


def is_sparse(path: Path) -> bool:
    return read_magic(path, 0, 4) == SPARSE_MAGIC


def is_ext4(path: Path) -> bool:
    return read_magic(path, 0x438, 2) == EXT4_MAGIC


def make_raw_image(src: Path, dst: Path) -> Path:
    if is_sparse(src):
        log("Detected Android sparse image")
        need("simg2img")
        run(["simg2img", str(src), str(dst)])
        return dst
    if is_ext4(src):
        log("Detected raw ext4 image")
        return src
    raise SystemExit(
        f"Unsupported system image format: {src}. "
        "Expected Android sparse ext4 or raw ext4."
    )


def mount_ro(image: Path, mountpoint: Path):
    mountpoint.mkdir(parents=True, exist_ok=True)
    run(["mount", "-o", "loop,ro", str(image), str(mountpoint)])


def umount(mountpoint: Path) -> None:
    subprocess.run(["umount", str(mountpoint)], check=True)


def copy_tree_from_mount(src: Path, dst: Path) -> None:
    """
    Copy a mounted Android filesystem while preserving symlinks and modes.
    GNU tar is used instead of 7z because 7z refuses ../ symlink targets
    such as system/framework/arm64/boot.vdex.
    """
    dst.mkdir(parents=True, exist_ok=True)
    archive = dst.parent / ".rootfs-copy.tar"
    try:
        run(["tar", "-C", str(src), "-cpf", str(archive), "."])
        run(["tar", "-C", str(dst), "-xpf", str(archive)])
    finally:
        archive.unlink(missing_ok=True)


def extract_apex_container(apex: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    run(["7z", "x", "-y", "-snl", str(apex), f"-o{out}"])


def flatten_apex(apex_dir: Path, rootfs: Path, work: Path) -> int:
    src_dir = rootfs / "system" / "apex"
    if not src_dir.is_dir():
        log("No system/apex directory; skipping APEX flattening")
        return 0

    out_dir = rootfs / "apex"
    out_dir.mkdir(parents=True, exist_ok=True)

    count = 0
    for apex in sorted(src_dir.glob("*.apex")):
        name = apex.stem
        container_dir = work / "apex" / name
        payload_mount = work / "apex-mnt" / name
        flattened = out_dir / name

        shutil.rmtree(container_dir, ignore_errors=True)
        flattened.mkdir(parents=True, exist_ok=True)

        log(f"Flattening APEX: {name}")
        extract_apex_container(apex, container_dir)

        payload = container_dir / "apex_payload.img"
        if not payload.is_file():
            # Some APEX containers are metadata-only / do not expose a payload.
            # Keep the extracted container files available in the flattened dir.
            for item in container_dir.iterdir():
                if item.name == "apex_payload.img":
                    continue
                target = flattened / item.name
                if item.is_symlink():
                    target.unlink(missing_ok=True)
                    target.symlink_to(os.readlink(item))
                elif item.is_dir():
                    shutil.copytree(item, target, symlinks=True, dirs_exist_ok=True)
                else:
                    shutil.copy2(item, target)
            count += 1
            continue

        raw_payload = work / "apex" / f"{name}.raw.img"
        payload_image = make_raw_image(payload, raw_payload)

        shutil.rmtree(payload_mount, ignore_errors=True)
        payload_mount.mkdir(parents=True, exist_ok=True)
        mounted = False
        try:
            mount_ro(payload_image, payload_mount)
            mounted = True
            copy_tree_from_mount(payload_mount, flattened)
        finally:
            if mounted:
                umount(payload_mount)

        count += 1

    return count


PROPERTIES = {
    "ro.kernel.qemu": "1",
    "ro.kernel.qemu.gles": "1",
    "ro.boot.selinux": "permissive",
    "ro.build.selinux": "0",
    "ro.hardware.gralloc": "vm",
    "ro.hardware.audio": "vm",
    "ro.hardware.camera": "vm",
    "ro.hardware.sensors": "vm",
    "ro.hardware.vulkan": "0",
    "debug.sf.disable_hwc": "1",
    "debug.sf.enable_gl_backpressure": "0",
    "debug.sf.latch_unsignaled": "1",
    "persist.sys.timezone": "Asia/Jakarta",
    "ro.vmers.version": "1.0.0",
    "ro.vmers.target_arch": "arm64-v8a",
}


def patch_build_prop(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"Missing required file: {path}")

    backup = path.with_name(path.name + ".orig")
    if not backup.exists():
        shutil.copy2(path, backup)

    lines = path.read_text(errors="replace").splitlines()
    keys = set(PROPERTIES)
    kept = [line for line in lines if not any(line.startswith(k + "=") for k in keys)]
    kept.extend(f"{k}={v}" for k, v in PROPERTIES.items())
    path.write_text("\n".join(kept) + "\n")
    log(f"Patched {path}")


def replace_with_sh_stub(path: Path) -> None:
    if path.is_symlink():
        log(f"Preserving symlink: {path} -> {os.readlink(path)}")
        return
    if not path.exists():
        log(f"Not present, skipping: {path}")
        return

    backup = path.with_name(path.name + ".orig")
    if not backup.exists():
        shutil.copy2(path, backup)

    path.unlink()
    path.write_text("#!/system/bin/sh\nexit 0\n")
    path.chmod(0o755)
    log(f"Sanitized {path}")


def sanitize_daemons(rootfs: Path) -> None:
    bindir = rootfs / "system" / "bin"
    for name in ("vold", "ueventd", "healthd", "netd"):
        replace_with_sh_stub(bindir / name)


def repair_framework_vdex(rootfs: Path) -> int:
    base = rootfs / "system" / "framework"
    repaired = 0
    for arch in ("arm", "arm64"):
        d = base / arch
        if not d.is_dir():
            continue
        for p in d.glob("*.vdex"):
            if p.is_symlink() or not p.is_file():
                continue
            if p.stat().st_size != 0:
                continue
            target = base / p.name
            if not target.is_file():
                continue
            p.unlink()
            p.symlink_to("../" + p.name)
            repaired += 1
            log(f"Repaired symlink: {p} -> ../{p.name}")
    return repaired


def verify(rootfs: Path) -> None:
    required = [
        rootfs / "system" / "build.prop",
        rootfs / "system" / "bin" / "init",
    ]
    for p in required:
        if not p.exists():
            raise SystemExit(f"Verification failed: missing {p}")

    checks = [
        rootfs / "system" / "framework" / "arm64" / "boot.vdex",
        rootfs / "system" / "framework" / "arm" / "boot.vdex",
    ]
    for p in checks:
        if p.exists() and not p.is_symlink():
            log(f"Warning: expected framework link is not a symlink: {p}")

    log("Rootfs verification passed")


def write_info(rootfs: Path, input_image: Path, apex_count: int, repaired: int) -> None:
    info = {
        "name": "vmers_a15_arm64",
        "version": "1.0.0",
        "android": "15",
        "sdk": 35,
        "arch": "arm64-v8a",
        "source_image": input_image.name,
        "apex_flattened": apex_count,
        "framework_vdex_links_repaired": repaired,
        "archive_format": "7z",
        "rootfs_dir": "rootfs",
    }
    (rootfs / "rom_info.json").write_text(json.dumps(info, indent=2) + "\n")


def package(rootfs: Path, output: Path, repo_root: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    # Keep the top-level rootfs/ directory in the archive.
    run(["7z", "a", "-y", "-snl", str(output), "rootfs"], cwd=repo_root)
    log(f"Created: {output} ({output.stat().st_size} bytes)")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--output", required=True, type=Path)
    ap.add_argument("--work-dir", type=Path, default=Path("rom_tools/work"))
    args = ap.parse_args()

    repo_root = Path.cwd().resolve()
    image = args.input.resolve()
    output = args.output.resolve()
    work = args.work_dir.resolve()

    if not image.is_file():
        raise SystemExit(f"Input image not found: {image}")

    for b in ("tar", "7z", "mount", "umount"):
        need(b)

    work.mkdir(parents=True, exist_ok=True)
    rootfs = work / "rootfs"
    shutil.rmtree(rootfs, ignore_errors=True)
    rootfs.mkdir(parents=True)

    raw = work / "system.raw.img"
    mountpoint = work / "system-mnt"
    mounted = False

    try:
        actual = make_raw_image(image, raw)

        log(f"Mounting system image: {actual}")
        mount_ro(actual, mountpoint)
        mounted = True

        # Copy the complete Android system filesystem, including symlinks.
        copy_tree_from_mount(mountpoint, rootfs)

    finally:
        if mounted:
            umount(mountpoint)

    patch_build_prop(rootfs / "system" / "build.prop")
    sanitize_daemons(rootfs)
    repaired = repair_framework_vdex(rootfs)

    apex_count = flatten_apex(rootfs, rootfs, work)
    write_info(rootfs, image, apex_count, repaired)
    verify(rootfs)

    # Stage the rootfs at repository root only for packaging, then remove it.
    staged = repo_root / "rootfs"
    if staged.exists():
        shutil.rmtree(staged)
    shutil.copytree(rootfs, staged, symlinks=True)

    try:
        package(staged, output, repo_root)
    finally:
        shutil.rmtree(staged, ignore_errors=True)

    log("DONE")


if __name__ == "__main__":
    main()
