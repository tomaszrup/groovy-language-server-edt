export function normalizeFsPath(inputPath: string): string {
    return inputPath.replaceAll('\\', '/').replace(/\/+$/, '').toLowerCase();
}

export function uriToFsPath(uriValue: string): string | undefined {
    try {
        const parsed = new URL(uriValue);
        let pathname = decodeURIComponent(parsed.pathname);
        if (/^\/[A-Za-z]:/.test(pathname)) {
            pathname = pathname.slice(1);
        }
        if (parsed.protocol === 'file:') {
            if (parsed.host) {
                pathname = `//${parsed.host}${pathname}`;
            }
            return pathname;
        }

        return pathname.startsWith('/') || /^[A-Za-z]:/.test(pathname) ? pathname : undefined;
    } catch {
        return undefined;
    }
}

export function isJdtWorkspaceUri(uriValue: string): boolean {
    const normalized = uriValue.toLowerCase().replaceAll('\\', '/');
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
        const bestProjectNorm = findBestProjectForEntry(normalizedEntry, projectRootMap);

        if (bestProjectNorm) {
            scores.set(bestProjectNorm, (scores.get(bestProjectNorm) ?? 0) + 1);
        }
    }

    const bestPathNorm = selectBestScoredProject(scores);
    return bestPathNorm ? projectRootMap.get(bestPathNorm) : undefined;
}

function findBestProjectForEntry(
    normalizedEntry: string,
    projectRootMap: Map<string, string>
): string | undefined {
    let bestProjectNorm: string | undefined;
    for (const [projectNorm] of projectRootMap) {
        if (!pathStartsWith(normalizedEntry, projectNorm)) {
            continue;
        }
        if (!bestProjectNorm || projectNorm.length > bestProjectNorm.length) {
            bestProjectNorm = projectNorm;
        }
    }
    return bestProjectNorm;
}

function selectBestScoredProject(scores: Map<string, number>): string | undefined {
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

    return bestPathNorm;
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
