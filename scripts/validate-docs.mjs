#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const errors = [];

function trackedMarkdownFiles() {
    const output = execFileSync(
        "git",
        ["ls-files", "--cached", "--others", "--exclude-standard", "--", "*.md"],
        { cwd: repoRoot, encoding: "utf8" },
    );
    return output.split(/\r?\n/).filter(Boolean).sort();
}

function githubSlug(raw, seen) {
    const base = raw
        .trim()
        .toLowerCase()
        .replace(/<[^>]+>/g, "")
        .replace(/[`*_~]/g, "")
        .replace(/[^\p{L}\p{N}\s-]/gu, "")
        .replace(/\s/g, "-");
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return count === 0 ? base : `${base}-${count}`;
}

function anchorsFor(file) {
    const seen = new Map();
    const anchors = new Set();
    for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
        const heading = line.match(/^#{1,6}\s+(.+?)\s*#*\s*$/);
        if (heading) {
            const slug = githubSlug(heading[1], seen);
            anchors.add(slug);
            // Existing GitHub links use both literal repeated-space hyphens and collapsed variants around removed
            // punctuation (for example '&' and em dashes). Accept either browser-visible form.
            anchors.add(slug.replace(/-+/g, "-"));
        }
    }
    return anchors;
}

function withoutFencedCode(text) {
    let fenced = false;
    return text
        .split(/\r?\n/)
        .map((line) => {
            if (/^\s*```/.test(line)) {
                fenced = !fenced;
                return "";
            }
            return fenced ? "" : line;
        })
        .join("\n");
}

function validateMarkdownLinks(markdownFiles) {
    const anchorCache = new Map();

    function validateTarget(relativeFile, source, target) {
        if (/^(?:https?:|mailto:)/i.test(target)) return;

        const hashIndex = target.indexOf("#");
        const rawPath = hashIndex >= 0 ? target.slice(0, hashIndex) : target;
        const anchor = hashIndex >= 0 ? decodeURIComponent(target.slice(hashIndex + 1)).toLowerCase() : null;
        const targetPath = rawPath
            ? resolve(dirname(source), decodeURIComponent(rawPath))
            : source;

        if (!existsSync(targetPath)) {
            errors.push(`${relativeFile}: missing local link target '${target}'`);
            return;
        }
        if (!anchor || !statSync(targetPath).isFile() || !targetPath.endsWith(".md")) return;

        let anchors = anchorCache.get(targetPath);
        if (!anchors) {
            anchors = anchorsFor(targetPath);
            anchorCache.set(targetPath, anchors);
        }
        if (!anchors.has(anchor)) errors.push(`${relativeFile}: missing Markdown anchor '${target}'`);
    }

    for (const relativeFile of markdownFiles) {
        const source = resolve(repoRoot, relativeFile);
        const text = withoutFencedCode(readFileSync(source, "utf8"));
        for (const match of text.matchAll(/\[[^\]]*]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
            validateTarget(relativeFile, source, match[1]);
        }
        for (const match of text.matchAll(/^\[[^\]]+]:\s+(\S+)\s*$/gm)) {
            validateTarget(relativeFile, source, match[1]);
        }
    }
}

function frontMatter(file) {
    const text = readFileSync(file, "utf8");
    const match = text.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/);
    if (!match) return null;
    const values = new Map();
    for (const line of match[1].split(/\r?\n/)) {
        const field = line.match(/^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*?)\s*$/);
        if (field) values.set(field[1], field[2]);
    }
    return values;
}

function validatePackets() {
    const packetDir = resolve(repoRoot, "docs/iterations");
    const packetNames = readdirSync(packetDir)
        .filter((name) => /^I\d{3,}-.+\.md$/.test(name))
        .sort();
    const registry = readFileSync(resolve(packetDir, "index.md"), "utf8");
    const ids = new Set();

    for (const name of packetNames) {
        const file = resolve(packetDir, name);
        const metadata = frontMatter(file);
        if (!metadata) {
            errors.push(`docs/iterations/${name}: missing YAML front matter`);
            continue;
        }

        const id = metadata.get("id");
        if (!id || !/^I\d{3,}$/.test(id)) {
            errors.push(`docs/iterations/${name}: invalid or missing id`);
        } else {
            if (!name.startsWith(`${id}-`)) {
                errors.push(`docs/iterations/${name}: filename does not match id '${id}'`);
            }
            if (ids.has(id)) errors.push(`docs/iterations/${name}: duplicate packet id '${id}'`);
            ids.add(id);
        }
        for (const required of ["title", "created"]) {
            if (!metadata.get(required)) errors.push(`docs/iterations/${name}: missing '${required}' metadata`);
        }
        if (!registry.includes(name)) {
            errors.push(`docs/iterations/${name}: packet is not registered in docs/iterations/index.md`);
        }

        // I001/I002 predate issue-number packet IDs and retain stable historical paths.
        if (id === "I001" || id === "I002") continue;
        const issue = metadata.get("issue");
        const issueMatch = issue?.match(/^https:\/\/github\.com\/jpalvarezl\/Konductor\/issues\/(\d+)$/);
        if (!issueMatch) {
            errors.push(`docs/iterations/${name}: missing canonical GitHub issue URL`);
            continue;
        }
        const expectedId = `I${issueMatch[1].padStart(3, "0")}`;
        if (id !== expectedId) {
            errors.push(`docs/iterations/${name}: id '${id}' must match issue #${issueMatch[1]} as '${expectedId}'`);
        }
    }
}

const markdownFiles = trackedMarkdownFiles();
validateMarkdownLinks(markdownFiles);
validatePackets();

if (errors.length > 0) {
    console.error(`Documentation validation failed (${errors.length} error${errors.length === 1 ? "" : "s"}):`);
    for (const error of errors) console.error(`- ${error}`);
    process.exit(1);
}

console.log(`Documentation validation passed (${markdownFiles.length} Markdown files checked).`);
