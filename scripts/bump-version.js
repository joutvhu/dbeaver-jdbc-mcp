const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const rootDir = path.resolve(__dirname, '..');

function parseSemver(v) {
    const m = v.match(/^v?(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/);
    if (!m) return null;
    return {
        major: parseInt(m[1], 10),
        minor: parseInt(m[2], 10),
        patch: parseInt(m[3], 10),
        prerelease: m[4] || ''
    };
}

function bumpSemver(current, type) {
    const s = parseSemver(current);
    if (!s) throw new Error(`Invalid current version: ${current}`);
    if (type === 'major') return `${s.major + 1}.0.0`;
    if (type === 'minor') return `${s.major}.${s.minor + 1}.0`;
    if (type === 'patch') return `${s.major}.${s.minor}.${s.patch + 1}`;
    return type.replace(/^v/, '');
}

function syncVersion() {
    const packageJsonPath = path.join(rootDir, 'package.json');
    const pomXmlPath = path.join(rootDir, 'pom.xml');
    const mcpServerJavaPath = path.join(rootDir, 'src', 'main', 'java', 'com', 'joutvhu', 'dbeaver', 'mcp', 'server', 'McpServer.java');
    const readmePath = path.join(rootDir, 'README.md');

    const pkg = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
    const currentVersion = pkg.version;

    // Detect if invoked from npm version lifecycle hook
    const isNpmVersionHook = process.env.npm_lifecycle_event === 'version';
    let targetVersion = null;
    const arg = process.argv[2];

    if (arg) {
        const lowerArg = arg.toLowerCase();
        if (['major', 'minor', 'patch'].includes(lowerArg)) {
            targetVersion = bumpSemver(currentVersion, lowerArg);
        } else {
            targetVersion = arg.replace(/^v/, '');
        }
    } else if (isNpmVersionHook && process.env.npm_package_version) {
        // Invoked via `npm version [patch|minor|major]` where npm already updated package.json
        targetVersion = process.env.npm_package_version.replace(/^v/, '');
    } else {
        // Default when running `npm run bump` without arguments: bump patch
        targetVersion = bumpSemver(currentVersion, 'patch');
    }

    console.log(`[bump-version] Synchronizing version: ${currentVersion} -> ${targetVersion}`);

    // 1. Update package.json
    pkg.version = targetVersion;
    fs.writeFileSync(packageJsonPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
    console.log(`[OK] Updated package.json`);

    // 2. Update pom.xml
    if (fs.existsSync(pomXmlPath)) {
        let pom = fs.readFileSync(pomXmlPath, 'utf8');
        const pomRegex = /(<groupId>com\.github\.joutvhu<\/groupId>\s*<artifactId>dbeaver-jdbc-mcp<\/artifactId>\s*<version>)[^<]+(<\/version>)/;
        if (pomRegex.test(pom)) {
            pom = pom.replace(pomRegex, `$1${targetVersion}$2`);
            fs.writeFileSync(pomXmlPath, pom, 'utf8');
            console.log(`[OK] Updated pom.xml`);
        } else {
            console.warn(`[WARN] Could not find project artifact version in pom.xml`);
        }
    }

    // 3. Update McpServer.java fallback constant
    if (fs.existsSync(mcpServerJavaPath)) {
        let javaContent = fs.readFileSync(mcpServerJavaPath, 'utf8');
        const javaRegex = /(serverVersion\s*=\s*")[^"]+(";)/;
        if (javaRegex.test(javaContent)) {
            javaContent = javaContent.replace(javaRegex, `$1${targetVersion}$2`);
            fs.writeFileSync(mcpServerJavaPath, javaContent, 'utf8');
            console.log(`[OK] Updated McpServer.java fallback version`);
        }
    }

    // 4. Update README.md jbang example
    if (fs.existsSync(readmePath)) {
        let readme = fs.readFileSync(readmePath, 'utf8');
        const readmeRegex = /(com\.github\.joutvhu:dbeaver-jdbc-mcp:)[0-9]+\.[0-9]+\.[0-9]+/;
        if (readmeRegex.test(readme)) {
            readme = readme.replace(readmeRegex, `$1${targetVersion}`);
            fs.writeFileSync(readmePath, readme, 'utf8');
            console.log(`[OK] Updated README.md`);
        }
    }

    // 5. Stage files with git if in a git repository
    try {
        execSync(`git add "${packageJsonPath}" "${pomXmlPath}" "${mcpServerJavaPath}" "${readmePath}"`, {
            cwd: rootDir,
            stdio: 'ignore'
        });
        console.log(`[OK] Staged updated files in git`);
    } catch (e) {
        // Ignore if git is not available or not staged
    }

    console.log(`[SUCCESS] Version successfully synchronized to ${targetVersion}\n`);
}

syncVersion();
