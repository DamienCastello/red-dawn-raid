import { readdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";

const assetsRoot = path.resolve("public/assets");

async function walk(dir) {
  const entries = await readdir(dir);
  const out = [];
  for (const name of entries) {
    const full = path.join(dir, name);
    const s = await stat(full);
    if (s.isDirectory()) out.push(...await walk(full));
    else out.push(full);
  }
  return out;
}

const files = await walk(assetsRoot);

// adapte si tu as d’autres extensions
const imgFiles = files.filter(f => /\.(png|jpg|jpeg|webp|gif|svg)$/i.test(f));

const urls = imgFiles
  .map(f => {
    const rel = path.relative(assetsRoot, f).replaceAll("\\", "/");
    return `/assets/${rel}`;
  })
  // évite d’inclure le manifest lui-même si tu le ranges dans assets
  .filter(u => !u.endsWith("/assets-manifest.json"));

const outPath = path.join(assetsRoot, "assets-manifest.json");
await writeFile(outPath, JSON.stringify(urls, null, 2), "utf-8");

console.log(`✅ assets-manifest.json généré: ${urls.length} images`);
