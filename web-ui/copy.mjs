import {
  copyFileSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
} from 'node:fs';
import { dirname, join } from 'node:path';

const SOURCE_DIR = './build/client';
const TARGET_DIR = '../dist/web-ui-static';

function copyDirectory(src, dest) {
  mkdirSync(dest, { recursive: true });

  const entries = readdirSync(src, { withFileTypes: true });
  for (const entry of entries) {
    const srcPath = join(src, entry.name);
    const destPath = join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDirectory(srcPath, destPath);
    } else {
      mkdirSync(dirname(destPath), { recursive: true });
      copyFileSync(srcPath, destPath);
    }
  }
}

try {
  console.log('Starting build output copy...');
  console.log('Source: ' + SOURCE_DIR);
  console.log('Target: ' + TARGET_DIR);

  try {
    statSync(SOURCE_DIR);
  } catch {
    console.error('Source directory not found: ' + SOURCE_DIR);
    console.error('Please run build first.');
    process.exit(1);
  }

  try {
    rmSync(TARGET_DIR, { recursive: true, force: true });
    console.log('Cleaned target directory');
  } catch {
    // ignore
  }

  copyDirectory(SOURCE_DIR, TARGET_DIR);
  console.log('Build output copied successfully');
} catch (error) {
  console.error('Copy failed:', error);
  process.exit(1);
}