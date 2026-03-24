import path from 'REDACTED_SECRET:path';
import { access, appendFile, mkdir, readFile } from 'REDACTED_SECRET:fs/promises';

export const exists = async (filePath) => {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
};

export const readJsonlFile = async (filePath) => {
  if (!(await exists(filePath))) {
    return [];
  }

  const raw = await readFile(filePath, 'utf8');
  return raw
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line));
};

export const appendJsonlRecord = async (filePath, record) => {
  await mkdir(path.dirname(filePath), { recursive: true });
  await appendFile(filePath, `${JSON.stringify(record)}\n`, 'utf8');
};
