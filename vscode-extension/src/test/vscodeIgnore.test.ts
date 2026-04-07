import { strict as assert } from 'node:assert';
import * as fs from 'node:fs';
import * as path from 'node:path';

describe('.vscodeignore packaging rules', () => {
    const ignoreFile = path.resolve(__dirname, '..', '..', '.vscodeignore');
    const ignoreEntries = new Set(
        fs.readFileSync(ignoreFile, 'utf8')
            .split(/\r?\n/u)
            .map(line => line.trim())
            .filter(line => line.length > 0 && !line.startsWith('#'))
    );

    it('excludes generated test artifacts and caches', () => {
        assert.ok(ignoreEntries.has('.playwright-cache/**'));
        assert.ok(ignoreEntries.has('.vscode-test/**'));
        assert.ok(ignoreEntries.has('coverage/**'));
        assert.ok(ignoreEntries.has('playwright-results/**'));
        assert.ok(ignoreEntries.has('*.vsix'));
    });

    it('keeps required packaged runtime assets included', () => {
        assert.ok(ignoreEntries.has('!server/**'));
        assert.ok(ignoreEntries.has('!syntaxes/**'));
        assert.ok(ignoreEntries.has('!dist/**'));
        assert.ok(ignoreEntries.has('!language-configuration.json'));
        assert.ok(ignoreEntries.has('!LICENSE'));
    });
});