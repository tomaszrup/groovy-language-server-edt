export function normalizeFsPath(inputPath: string): string {
    return inputPath.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
}

export function isJdtWorkspaceUri(uriValue: string): boolean {
    const normalized = uriValue.toLowerCase().replace(/\\/g, '/');
    return normalized.includes('/jdt_ws/');
}

export function pathStartsWith(pathValue: string, parentValue: string): boolean {
    return pathValue === parentValue || pathValue.startsWith(`${parentValue}/`);
}

export function inferProjectPathFromEntries(
    entries: string[],
    projectRootMap: Map<string, string>
): string | undefined {
    if (entries.length === 0 || projectRootMap.size === 0) return undefined;

    const scores = new Map<string, number>();
    for (const entry of entries) {
        const normalizedEntry = normalizeFsPath(entry);
        let bestProjectNorm: string | undefined;
        for (const [projectNorm] of projectRootMap) {
            if (pathStartsWith(normalizedEntry, projectNorm)) {
                if (!bestProjectNorm || projectNorm.length > bestProjectNorm.length) {
                    bestProjectNorm = projectNorm;
                }
            }
        }

        if (bestProjectNorm) {
            scores.set(bestProjectNorm, (scores.get(bestProjectNorm) ?? 0) + 1);
        }
    }

    let bestPathNorm: string | undefined;
    let bestScore = -1;

    for (const [projectNorm, score] of scores) {
        if (score > bestScore) {
            bestPathNorm = projectNorm;
            bestScore = score;
        } else if (score === bestScore && bestPathNorm && projectNorm.length > bestPathNorm.length) {
            bestPathNorm = projectNorm;
        }
    }

    return bestPathNorm ? projectRootMap.get(bestPathNorm) : undefined;
}

export function getConfigNameForPlatform(platform: NodeJS.Platform): string {
    switch (platform) {
        case 'win32':
            return 'config_win';
        case 'darwin':
            return 'config_mac';
        default:
            return 'config_linux';
    }
}
