import { strict as assert } from 'assert';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { createExtensionHarness } from './extensionHarness';

describe('extension core', () => {
    afterEach(function () {
        (this as any).harness?.cleanup();
    });

    it('updates the status bar for representative states', function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;

        harness.testing.updateStatusBar('WaitingForJava', 'Waiting');
        assert.equal(harness.statusBarItem.text, '$(sync~spin) Groovy: Waiting for Java');

        harness.testing.updateStatusBar('Ready');
        assert.equal(harness.statusBarItem.text, '$(check) Groovy: Ready');

        harness.testing.updateStatusBar('Error', 'Boom');
        assert.equal(harness.statusBarItem.text, '$(error) Groovy: Error');

        harness.testing.updateStatusBar('Stopped');
        assert.equal(harness.statusBarItem.command, 'groovy.restartServer');
        assert.equal(harness.statusBarItem.showCount >= 4, true);
    });

    it('handles Java extension activation branches', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;

        assert.equal(await harness.testing.activateJavaExtension(), false);

        harness.registerExtension('redhat.java', {
            isActive: false,
            activate: async () => {
                throw new Error('activate failed');
            },
        });
        assert.equal(await harness.testing.activateJavaExtension(), false);

        harness.registerExtension('redhat.java', {
            isActive: true,
            exports: null,
        });
        assert.equal(await harness.testing.activateJavaExtension(), false);

        harness.registerExtension('redhat.java', {
            isActive: true,
            exports: {
                apiVersion: '1.0.0',
                serverMode: 'Standard',
            },
        });
        assert.equal(await harness.testing.activateJavaExtension(), true);
    });

    it('validates Java executable resolution and version requirements', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'groovy-ls-extension-core-'));
        const jdkDir = path.join(tempDir, 'jdk');
        const binDir = path.join(jdkDir, 'bin');
        fs.mkdirSync(binDir, { recursive: true });
        const javaExecutable = path.join(binDir, 'java');
        fs.writeFileSync(javaExecutable, '', 'utf8');
        const originalJavaHome = process.env.JAVA_HOME;
        const originalPath = process.env.PATH;

        try {
            process.env.JAVA_HOME = '';
            process.env.PATH = '';
            assert.equal(await harness.testing.resolveJavaExecutableOrShowError(), undefined);

            harness.setConfiguration({ 'java.home': jdkDir });
            harness.setExecFileImplementation((_file, _args, _options, callback) => {
                callback(null, '', 'openjdk version "17.0.9"');
            });
            assert.equal(await harness.testing.resolveJavaExecutableOrShowError(), undefined);

            harness.setExecFileImplementation((_file, _args, _options, callback) => {
                callback(null, '', 'openjdk version "21.0.4"');
            });
            assert.equal(await harness.testing.resolveJavaExecutableOrShowError(), javaExecutable);
        } finally {
            process.env.JAVA_HOME = originalJavaHome;
            process.env.PATH = originalPath;
            fs.rmSync(tempDir, { recursive: true, force: true });
        }
    });

    it('inspects java major versions and stops active language clients', async function () {
        const harness = createExtensionHarness();
        (this as any).harness = harness;

        harness.setExecFileImplementation((_file, _args, _options, callback) => {
            callback(null, '', 'openjdk version "21.0.4"');
        });
        assert.equal(await harness.testing.getJavaMajorVersion('/jdk/bin/java'), 21);

        harness.setExecFileImplementation((_file, _args, _options, callback) => {
            callback(null, '', 'unexpected output');
        });
        assert.equal(await harness.testing.getJavaMajorVersion('/jdk/bin/java'), undefined);

        harness.setExecFileImplementation((_file, _args, _options, callback) => {
            callback(new Error('spawn failed'), '', '');
        });
        assert.equal(await harness.testing.getJavaMajorVersion('/jdk/bin/java'), undefined);

        const session = harness.testing.createServerSessionScope();
        let disposed = false;
        harness.testing.addSessionDisposables(session, { dispose: () => { disposed = true; } });
        harness.testing.setActiveServerSession(session);
        const client = new harness.FakeLanguageClient('groovy', 'Groovy Language Server', {}, {});
        let stopped = false;
        client.stop = async () => {
            stopped = true;
        };
        harness.testing.setClient(client);

        await harness.testing.stopLanguageServer();

        assert.equal(disposed, true);
        assert.equal(stopped, true);
        assert.equal(harness.testing.getClient(), undefined);
    });
});