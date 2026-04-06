import { strict as assert } from 'assert';
import {
    buildGroovyDidChangeConfigurationNotification,
    shouldForwardGroovyConfigurationChange,
    type GroovyConfigurationReader,
} from '../groovyConfiguration';

describe('groovyConfiguration', () => {
    it('builds the default Groovy didChangeConfiguration payload', () => {
        const config = createConfigReader({});

        assert.deepEqual(buildGroovyDidChangeConfigurationNotification(config), {
            settings: {
                groovy: {
                    ls: {
                        logLevel: 'error',
                    },
                    format: {
                        settingsUrl: null,
                        enabled: true,
                    },
                    inlayHints: {
                        variableTypes: { enabled: true },
                        parameterNames: { enabled: true },
                        closureParameterTypes: { enabled: true },
                        methodReturnTypes: { enabled: true },
                    },
                },
            },
        });
    });

    it('builds the Groovy didChangeConfiguration payload from custom settings', () => {
        const config = createConfigReader({
            'ls.logLevel': 'info',
            'format.settingsUrl': '/workspace/formatter.xml',
            'format.enabled': false,
            'inlayHints.variableTypes.enabled': false,
            'inlayHints.parameterNames.enabled': false,
            'inlayHints.closureParameterTypes.enabled': false,
            'inlayHints.methodReturnTypes.enabled': false,
        });

        assert.deepEqual(buildGroovyDidChangeConfigurationNotification(config), {
            settings: {
                groovy: {
                    ls: {
                        logLevel: 'info',
                    },
                    format: {
                        settingsUrl: '/workspace/formatter.xml',
                        enabled: false,
                    },
                    inlayHints: {
                        variableTypes: { enabled: false },
                        parameterNames: { enabled: false },
                        closureParameterTypes: { enabled: false },
                        methodReturnTypes: { enabled: false },
                    },
                },
            },
        });
    });

    it('forwards only relevant Groovy configuration changes', () => {
        assert.equal(
            shouldForwardGroovyConfigurationChange(createChangeEvent(['groovy.ls.logLevel'])),
            true
        );
        assert.equal(
            shouldForwardGroovyConfigurationChange(createChangeEvent(['groovy.format'])),
            true
        );
        assert.equal(
            shouldForwardGroovyConfigurationChange(createChangeEvent(['groovy.inlayHints'])),
            true
        );
        assert.equal(
            shouldForwardGroovyConfigurationChange(createChangeEvent(['files.autoSave'])),
            false
        );
    });
});

function createConfigReader(values: Record<string, unknown>): GroovyConfigurationReader {
    return {
        get<T>(section: string, defaultValue?: T): T | undefined {
            return (section in values ? values[section] : defaultValue) as T | undefined;
        },
    };
}

function createChangeEvent(affectedSections: string[]) {
    return {
        affectsConfiguration(section: string): boolean {
            return affectedSections.includes(section);
        },
    };
}