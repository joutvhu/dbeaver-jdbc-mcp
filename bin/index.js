#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');

function findJavaExecutable() {
    // 1. Check JAVA_HOME
    if (process.env.JAVA_HOME) {
        const binJava = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
        if (fs.existsSync(binJava)) {
            return binJava;
        }
    }

    // 2. Check platform-specific DBeaver bundled JRE
    if (process.platform === 'win32') {
        // Query registry on Windows
        try {
            const queryCmd = 'reg query HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall 2>nul';
            const output = execSync(queryCmd, { encoding: 'utf8' });
            const dbeaverKeys = output.split('\n').filter(line => line.toLowerCase().includes('dbeaver'));
            for (const key of dbeaverKeys) {
                const trimmedKey = key.trim();
                if (!trimmedKey) continue;
                try {
                    const locOutput = execSync(`reg query "${trimmedKey}" /v InstallLocation 2>nul`, { encoding: 'utf8' });
                    const match = locOutput.match(/InstallLocation\s+REG_SZ\s+(.*)/i);
                    if (match && match[1]) {
                        const installPath = match[1].trim();
                        const regJava = path.join(installPath, 'jre', 'bin', 'java.exe');
                        if (fs.existsSync(regJava)) return regJava;
                    }
                } catch (e) {}
            }
        } catch (e) {}

        // Check common Windows drive locations
        const drives = ['C', 'D', 'E', 'F', 'G'];
        for (const drive of drives) {
            const p1 = `${drive}:\\Program Files\\DBeaver\\jre\\bin\\java.exe`;
            if (fs.existsSync(p1)) return p1;
            const p2 = `${drive}:\\Program Files (x86)\\DBeaver\\jre\\bin\\java.exe`;
            if (fs.existsSync(p2)) return p2;
        }

        if (process.env.LOCALAPPDATA) {
            const localAppJava = path.join(process.env.LOCALAPPDATA, 'Programs', 'DBeaver', 'jre', 'bin', 'java.exe');
            if (fs.existsSync(localAppJava)) return localAppJava;
        }
    } else if (process.platform === 'darwin') {
        const macLocations = [
            '/Applications/DBeaver.app/Contents/Eclipse/jre/Home/bin/java',
            '/Applications/DBeaver.app/Contents/jre/bin/java'
        ];
        for (const p of macLocations) {
            if (fs.existsSync(p)) return p;
        }
    } else {
        const linuxLocations = [
            '/usr/share/dbeaver/jre/bin/java',
            '/usr/lib/dbeaver/jre/bin/java',
            '/opt/dbeaver/jre/bin/java'
        ];
        for (const p of linuxLocations) {
            if (fs.existsSync(p)) return p;
        }
    }

    // 3. Fallback to system java in PATH
    return 'java';
}

function resolveJarPath() {
    // 1. Check target folder (maven build output)
    const targetJar = path.join(__dirname, '..', 'target', 'dbeaver-mcp.jar');
    if (fs.existsSync(targetJar)) {
        return targetJar;
    }

    // 2. Check root directory
    const rootJar = path.join(__dirname, '..', 'dbeaver-mcp.jar');
    if (fs.existsSync(rootJar)) {
        return rootJar;
    }

    throw new Error('dbeaver-mcp.jar not found. Please run "mvn package" first.');
}

function main() {
    const javaBin = findJavaExecutable();
    const jarPath = resolveJarPath();

    const args = ['-jar', jarPath, ...process.argv.slice(2)];

    const child = spawn(javaBin, args, {
        stdio: ['inherit', 'inherit', 'inherit'],
        env: process.env
    });

    child.on('error', (err) => {
        console.error(`[dbeaver-mcp] Failed to spawn Java process: ${err.message}`);
        console.error(`[dbeaver-mcp] Please verify that Java or DBeaver is installed.`);
        process.exit(1);
    });

    child.on('exit', (code, signal) => {
        if (signal) {
            process.kill(process.pid, signal);
        } else {
            process.exit(code || 0);
        }
    });
}

main();
