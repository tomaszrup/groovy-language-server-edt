import {
    isJdtWorkspaceUri,
    normalizeFsPath,
    uriToFsPath,
} from './utils';
import { resolveProjectPathFromUri } from './classpathUtils';
import type { StartupClasspathTarget } from './startupTracking';

export interface JavaProjectTargetBuildResult {
    targets: StartupClasspathTarget[];
    nonRootProjectCount: number;
    skippedJdtWorkspaceUris: string[];
    skippedRootWorkspaceUris: string[];
}

export function buildJavaProjectTargets(
    projectUris: string[],
    projectRootMap: Map<string, string>,
    workspaceRootPath: string
): JavaProjectTargetBuildResult {
    const skippedJdtWorkspaceUris: string[] = [];
    const skippedRootWorkspaceUris: string[] = [];
    const nonRootCount = countNonRootProjects(projectUris, workspaceRootPath);
    const targets: StartupClasspathTarget[] = [];

    for (const uri of projectUris) {
        const decision = getJavaProjectInclusionDecision(uri, workspaceRootPath, nonRootCount);
        if (decision === 'skip-jdt-workspace') {
            skippedJdtWorkspaceUris.push(uri);
            continue;
        }
        if (decision === 'skip-root-workspace') {
            skippedRootWorkspaceUris.push(uri);
            continue;
        }

        targets.push({
            requestUri: uri,
            projectUri: uri,
            projectPath: resolveProjectPathFromUri(uri, projectRootMap),
            source: 'java.project.getAll',
        });
    }

    return {
        targets,
        nonRootProjectCount: nonRootCount,
        skippedJdtWorkspaceUris,
        skippedRootWorkspaceUris,
    };
}

export function collectKnownTargetPaths(targets: StartupClasspathTarget[]): Set<string> {
    const knownPaths = new Set<string>();

    for (const target of targets) {
        const pathValue = target.projectPath ?? uriToFsPath(target.projectUri);
        if (pathValue) {
            knownPaths.add(normalizeFsPath(pathValue));
        }
    }

    return knownPaths;
}

export function createWorkspaceFolderFallbackTarget(
    folderUri: string,
    folderPath: string,
    representativeUri?: string
): StartupClasspathTarget {
    return {
        requestUri: representativeUri ?? folderUri,
        projectUri: folderUri,
        projectPath: folderPath,
        source: 'workspace-folder-fallback',
    };
}

type JavaProjectInclusionDecision = 'include' | 'skip-jdt-workspace' | 'skip-root-workspace';

function countNonRootProjects(projectUris: string[], workspaceRootPath: string): number {
    return projectUris.filter(uri => {
        if (isJdtWorkspaceUri(uri)) {
            return false;
        }

        const fsPath = uriToFsPath(uri);
        return !fsPath || normalizeFsPath(fsPath) !== workspaceRootPath;
    }).length;
}

function getJavaProjectInclusionDecision(
    uri: string,
    workspaceRootPath: string,
    nonRootCount: number
): JavaProjectInclusionDecision {
    if (isJdtWorkspaceUri(uri)) {
        return 'skip-jdt-workspace';
    }

    const fsPath = uriToFsPath(uri);
    if (fsPath && normalizeFsPath(fsPath) === workspaceRootPath && nonRootCount > 0) {
        return 'skip-root-workspace';
    }

    return 'include';
}