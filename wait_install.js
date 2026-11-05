const cp = require('child_process');

const baseline = [
    'com.starmakerinteractive.starmaker',
    'org.ck12.flexi',
    'com.quiz.earn.money.game',
    'com.adobe.lrmobile',
    'com.adobe.aqua',
    'com.google.android.contactkeys',
    'com.google.android.verifier',
    'com.lenskart.app',
    'com.rayole.cashmafia2'
];

function getPackages() {
    try {
        const cmd = '& "C:\\Program Files\\BlueStacks_nxt\\HD-Adb.exe" -s emulator-5554 shell pm list packages -3';
        const out = cp.execSync(cmd, { shell: 'powershell.exe' }).toString();
        const lines = out.split(/\r?\n/);
        const pkgs = [];
        for (const line of lines) {
            const m = line.trim().match(/^package:(.+)$/);
            if (m) pkgs.push(m[1].trim());
        }
        return pkgs;
    } catch (e) {
        console.error('Error getting packages:', e.message);
        return [];
    }
}

async function main() {
    console.log('Waiting for new package to be installed...');
    for (let i = 0; i < 40; i++) {
        const pkgs = getPackages();
        const newPkgs = pkgs.filter(p => !baseline.includes(p));
        if (newPkgs.length > 0) {
            console.log('SUCCESS: Found new installed package(s):', newPkgs);
            process.exit(0);
        }
        console.log(`Checking... (${(i + 1) * 3}s elapsed). Currently installed: ${pkgs.length} packages`);
        await new Promise(r => setTimeout(r, 3000));
    }
    console.log('Timed out waiting for new package.');
    process.exit(1);
}

main();
