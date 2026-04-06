export interface GroovyConfigurationReader {
    get<T>(section: string, defaultValue?: T): T | undefined;
}

export interface GroovyConfigurationChangeEventLike {
    affectsConfiguration(section: string): boolean;
}

export interface GroovyDidChangeConfigurationNotification {
    settings: {
        groovy: {
            ls: {
                logLevel: string;
            };
            format: {
                settingsUrl: string | null;
                enabled: boolean;
            };
            inlayHints: {
                variableTypes: { enabled: boolean };
                parameterNames: { enabled: boolean };
                closureParameterTypes: { enabled: boolean };
                methodReturnTypes: { enabled: boolean };
            };
        };
    };
}

export function buildGroovyDidChangeConfigurationNotification(
    config: GroovyConfigurationReader
): GroovyDidChangeConfigurationNotification {
    return {
        settings: {
            groovy: {
                ls: {
                    logLevel: config.get('ls.logLevel', 'error') ?? 'error',
                },
                format: {
                    settingsUrl: config.get('format.settingsUrl') ?? null,
                    enabled: config.get('format.enabled', true) ?? true,
                },
                inlayHints: {
                    variableTypes: {
                        enabled: config.get('inlayHints.variableTypes.enabled', true) ?? true,
                    },
                    parameterNames: {
                        enabled: config.get('inlayHints.parameterNames.enabled', true) ?? true,
                    },
                    closureParameterTypes: {
                        enabled: config.get('inlayHints.closureParameterTypes.enabled', true) ?? true,
                    },
                    methodReturnTypes: {
                        enabled: config.get('inlayHints.methodReturnTypes.enabled', true) ?? true,
                    },
                },
            },
        },
    };
}

export function shouldForwardGroovyConfigurationChange(
    event: GroovyConfigurationChangeEventLike
): boolean {
    return event.affectsConfiguration('groovy.ls.logLevel')
        || event.affectsConfiguration('groovy.format')
        || event.affectsConfiguration('groovy.inlayHints');
}