#!/usr/bin/env node
/**
 * 把国内 Maven 镜像插到 Capacitor 自带子工程的 repositories 前面。
 *
 * 为什么必须做这一步（而不是只改根 build.gradle）：
 *   Capacitor 的 `capacitor/build.gradle` 与 `capacitor-cordova-android-plugins/build.gradle`
 *   各自带一个 `buildscript { repositories { google() ... } }`。根 build.gradle 里的
 *   repositories 影响不到它们，于是 Gradle 会去直连 dl.google.com —— 这台机器上被墙，
 *   表现为 `Connect timed out` **而不是** "not found"，所以 Gradle 不会顺延到下一个仓库，
 *   而是直接构建失败。必须把镜像插到**列表最前面**。
 *
 * 幂等：已含镜像 URL 就跳过；找不到 `google()`（说明 Capacitor 模板变了）就报错退出，
 * 不做「静默不生效」的事。
 *
 * 用法：node scripts/patch-capacitor-repos.cjs
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const MIRRORS = [
  'https://mirrors.cloud.tencent.com/nexus/repository/maven-public/',
  'https://maven.aliyun.com/repository/google',
  'https://maven.aliyun.com/repository/public',
];

const MARKER = MIRRORS[0];

const TARGETS = [
  // Capacitor 官方库（在 node_modules 里，npm install 会被覆盖，所以是 postinstall 跑）
  'node_modules/@capacitor/android/capacitor/build.gradle',
  // cap sync 会重新生成这个文件，所以每次构建前都要再打一次
  'android/capacitor-cordova-android-plugins/build.gradle',
];

/** 在每一个 `google()` 行前面插入镜像行。 */
function patch(content) {
  const googleLine = /^([ \t]*)google\(\)[ \t]*$/gm;
  let hits = 0;
  const patched = content.replace(googleLine, (line, indent) => {
    hits += 1;
    const mavenLines = MIRRORS.map((url) => `${indent}maven { url '${url}' }`).join('\n');
    return `${mavenLines}\n${line}`;
  });
  return { patched, hits };
}

let changed = 0;
let failed = 0;

for (const relative of TARGETS) {
  const file = path.join(__dirname, '..', relative);
  if (!fs.existsSync(file)) {
    console.log(`  – 跳过（不存在）：${relative}`);
    continue;
  }

  const original = fs.readFileSync(file, 'utf8');

  if (original.includes(MARKER)) {
    console.log(`  ✔ 已打过补丁：${relative}`);
    continue;
  }

  const { patched, hits } = patch(original);

  if (hits === 0) {
    console.error(`  ✘ ${relative} 里没有找到 google()，Capacitor 模板可能变了，补丁需要更新`);
    failed += 1;
    continue;
  }

  fs.writeFileSync(file, patched, 'utf8');
  console.log(`  ✔ 已插入镜像（${hits} 处 repositories）：${relative}`);
  changed += 1;
}

if (failed > 0) {
  process.exit(1);
}
console.log(changed === 0 ? '  镜像补丁：无需改动' : `  镜像补丁：改了 ${changed} 个文件`);
