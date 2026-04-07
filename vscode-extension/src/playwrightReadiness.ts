const BLOCKING_NOTIFICATION_PATTERNS = [
    /collect usage data/i,
    /open the repository/i,
];

const DISMISSIBLE_NOTIFICATION_PATTERNS = [
    /Opening Java Projects/i,
];

const CLASSPATH_READY_PATTERNS = [
    /Sent usable classpath for 1\/1 project\(s\)/i,
    /Sent groovy\/classpathBatchComplete to server \(delivered 1 project\(s\) on attempt \d+\)/i,
];

export interface NotificationClassification {
    blocking: string[];
    dismissible: string[];
}

export function classifyDevHostNotifications(texts: string[]): NotificationClassification {
    const blocking: string[] = [];
    const dismissible: string[] = [];

    for (const text of texts) {
        if (BLOCKING_NOTIFICATION_PATTERNS.some(pattern => pattern.test(text))) {
            blocking.push(text);
            continue;
        }
        if (DISMISSIBLE_NOTIFICATION_PATTERNS.some(pattern => pattern.test(text))) {
            dismissible.push(text);
        }
    }

    return { blocking, dismissible };
}

export function hasSampleClasspathReadyOutput(outputText: string): boolean {
    return CLASSPATH_READY_PATTERNS.some(pattern => pattern.test(outputText));
}