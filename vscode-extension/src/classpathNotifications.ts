export interface ClasspathNotificationPayload {
    projectUri: string;
    projectPath?: string;
    entries: string[];
    hasJarEntries: boolean;
}

export function recordClasspathNotificationFingerprint(
    sentFingerprints: Map<string, string>,
    payload: ClasspathNotificationPayload
): boolean {
    const projectKey = payload.projectUri || payload.projectPath || 'unknown-project';
    const fingerprint = JSON.stringify({
        projectPath: payload.projectPath ?? '',
        entries: [...payload.entries].sort(),
        hasJarEntries: payload.hasJarEntries,
    });

    if (sentFingerprints.get(projectKey) === fingerprint) {
        return false;
    }

    sentFingerprints.set(projectKey, fingerprint);
    return true;
}