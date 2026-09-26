#!/usr/bin/env node

import { TextDecoder } from "node:util";
import { convertStudioGraph } from "./convert.mjs";

const MAX_INPUT_BYTES = 1024 * 1024;

if (process.argv.length !== 4 || process.argv[2] !== "convert" || process.argv[3] !== "-") {
  process.stderr.write("Usage: node src/cli.mjs convert -\n");
  process.exitCode = 2;
} else {
  try {
    const chunks = [];
    let size = 0;
    for await (const chunk of process.stdin) {
      size += chunk.length;
      if (size > MAX_INPUT_BYTES) throw new Error("Studio Graph input exceeds 1048576 bytes");
      chunks.push(chunk);
    }
    const text = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks));
    const graph = JSON.parse(text);
    process.stdout.write(`${JSON.stringify(convertStudioGraph(graph))}\n`);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
