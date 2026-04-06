import { strict as assert } from 'assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { prepareWritableConfigDir } from '../serverConfig';

describe('serverConfig', () => {
    let tempDir: string;

    beforeEach(() => {
        tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-config-test-'));
    });

    afterEach(() => {
        fs.rmSync(tempDir, { recursive: true, force: true });
    });

    it('copies the bundled Equinox config into writable storage', () => {
        const serverDir = createServerDir(tempDir, 'osgi.bundles=alpha\n');
        const storagePath = path.join(tempDir, 'storage');

        const result = prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });

        assert.equal(result.bundledConfigDir, path.join(serverDir, 'config_linux'));
        assert.equal(fs.readFileSync(path.join(result.configDir, 'config.ini'), 'utf8'), 'osgi.bundles=alpha\n');
        assert.equal(result.resetConfig, false);
    });

    it('refreshes the writable config when the bundled template changes', () => {
        const serverDir = createServerDir(tempDir, 'osgi.bundles=alpha\n');
        const storagePath = path.join(tempDir, 'storage');

        const first = prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });
        fs.writeFileSync(path.join(first.configDir, 'runtime.cache'), 'keep-me', 'utf8');
        fs.writeFileSync(path.join(serverDir, 'config_linux', 'config.ini'), 'osgi.bundles=beta\n', 'utf8');

        const second = prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });

        assert.equal(second.resetConfig, true);
        assert.equal(second.reason, 'bundled Equinox config changed');
        assert.equal(fs.readFileSync(path.join(second.configDir, 'config.ini'), 'utf8'), 'osgi.bundles=beta\n');
        assert.equal(fs.existsSync(path.join(second.configDir, 'runtime.cache')), false);
    });

    it('refreshes the writable config when the extension version changes', () => {
        const serverDir = createServerDir(tempDir, 'osgi.bundles=alpha\n');
        const storagePath = path.join(tempDir, 'storage');

        prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });

        const result = prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.13',
        });

        assert.equal(result.resetConfig, true);
        assert.equal(result.reason, 'extension version 1.2.12 -> 1.2.13');
    });

    it('refreshes the writable config when the state marker is invalid', () => {
        const serverDir = createServerDir(tempDir, 'osgi.bundles=alpha\n');
        const storagePath = path.join(tempDir, 'storage');

        prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });
        fs.writeFileSync(path.join(storagePath, 'groovy_config_state.json'), '{invalid json', 'utf8');

        const result = prepareWritableConfigDir({
            serverDir,
            storagePath,
            platform: 'linux',
            extensionVersion: '1.2.12',
        });

        assert.equal(result.resetConfig, true);
        assert.equal(result.reason, 'invalid Equinox config marker');
    });
});

function createServerDir(rootDir: string, configIni: string): string {
    const serverDir = path.join(rootDir, 'server');
    const configDir = path.join(serverDir, 'config_linux');
    fs.mkdirSync(configDir, { recursive: true });
    fs.writeFileSync(path.join(configDir, 'config.ini'), configIni, 'utf8');
    return serverDir;
}
