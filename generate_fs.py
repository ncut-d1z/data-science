#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os

# 配置
SOURCE_DIR = './dev'      # 要遍历的子目录
OUTPUT_FILE = 'fs.md'    # 输出文件名

# 文件扩展名到 Markdown 语言标签的映射
EXT_MAP = {
    '.xml': 'xml',
    '.java': 'java',
    '.sh': 'bash',
    '.py': 'python',
    '.sql': 'sql',
}

# 需要忽略的目录 (避免遍历构建产物或版本控制目录)
IGNORE_DIRS = {'.git', '.idea', 'target', '__pycache__', 'build', 'bin', '.vscode'}

# 需要忽略的文件扩展名 (避免读取二进制文件)
IGNORE_EXTS = {'.jar', '.class', '.pyc', '.png', '.jpg', '.jpeg', '.gz', '.tar', '.zip', '.exe', '.so'}

def generate_fs_text():
    # 检查源目录是否存在
    if not os.path.exists(SOURCE_DIR):
        print(f"Error: 目录 {SOURCE_DIR} 不存在。")
        return

    print(f"开始遍历 {SOURCE_DIR} ...")

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as out_f:
        # os.walk 递归遍历目录
        for root, dirs, files in os.walk(SOURCE_DIR):
            # 修改 dirs 列表，原地移除不需要遍历的目录
            dirs[:] = [d for d in dirs if d not in IGNORE_DIRS]

            for file in files:
                file_ext = os.path.splitext(file)[1].lower()

                # 跳过二进制文件
                if file_ext in IGNORE_EXTS:
                    continue

                # 拼接完整路径
                full_path = os.path.join(root, file)

                # 获取相对路径 (例如: dev\pom.xml 或 dev/pom.xml)
                # 使用 os.path.relpath 确保路径是相对于当前运行目录的
                rel_path = os.path.relpath(full_path, start='.')

                # 根据后缀判断代码块语言，默认为空
                try:
                    lang = EXT_MAP[file_ext]
                except KeyError:
                    continue

                if not lang:
                    continue

                try:
                    # 尝试以 UTF-8 读取文件
                    with open(full_path, 'r', encoding='utf-8') as in_f:
                        content = in_f.read()

                    # 写入文件名
                    out_f.write(f"文件 {rel_path} 的内容为:\n")

                    # 写入代码块开始
                    out_f.write(f"```{lang}\n")

                    # 写入内容
                    out_f.write(content)

                    # 确保内容后有换行，防止 ``` 接在最后一行字后面
                    if content and not content.endswith('\n'):
                        out_f.write('\n')

                    # 写入代码块结束及空行
                    out_f.write("```\n\n")

                    print(f"[OK] 已写入: {rel_path}")

                except UnicodeDecodeError:
                    print(f"[SKIP] 无法读取文件 (可能是二进制): {rel_path}")
                except Exception as e:
                    print(f"[ERROR] 读取 {rel_path} 失败: {e}")

if __name__ == '__main__':
    generate_fs_text()
    print(f"\n完成！文件结构和内容已保存至: {os.path.abspath(OUTPUT_FILE)}")
