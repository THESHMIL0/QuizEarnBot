$path = "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\QuizEarnAccessibilityService.java"
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)

# 1. isForbiddenElement: block bare skip and milestone
$target1 = @"
        // Ad counters/labels ("Ad 1 of 2", "Ad 2 of 2", "Ad", "Sponsored", "Advertisement")
"@
$replace1 = @"
        // NEVER touch Skip on offer milestones or tasks!
        if (t.equals("skip") || d.equals("skip") || t.equals("skipping") ||
            d.contains("milestone") || d.contains("task") || d.contains("proceed") || d.contains("proof")) {
            return true;
        }

        // Ad counters/labels ("Ad 1 of 2", "Ad 2 of 2", "Ad", "Sponsored", "Advertisement")
"@
$content = $content.Replace($target1, $replace1)

# 2. isSkipElement: remove bare skip
$target2 = @"
        // Explicit skip text or description (e.g. "Skip", "Skip Ad", ">> Skip to Google Play", "Skip to Play Store")
        if (t.contains("skip") || d.contains("skip")) {
            return true;
        }
"@
$replace2 = @"
        // Explicit skip text or description ONLY (never bare "skip" which matches milestones!)
        if (t.contains("skip ad") || d.contains("skip ad") || t.contains("skip video") || d.contains("skip video") ||
            t.contains(">> skip to google play") || d.contains(">> skip to google play") ||
            t.contains("skip to play store") || d.contains("skip to play store")) {
            return true;
        }
"@
$content = $content.Replace($target2, $replace2)

# 3. isCloseElement: remove d.equals("skip") || d.startsWith("skip")
$target3 = @"
        if (d.equals("close ad") || d.equals("close") || d.equals("close button") || 
            d.equals("close video") || d.equals("dismiss") || d.contains("close ad") ||
            d.contains("close advertisement") || d.startsWith("close") ||
            d.contains("skip ad") || d.equals("skip") || d.startsWith("skip")) {
            return true;
        }
"@
$replace3 = @"
        if (d.equals("close ad") || d.equals("close") || d.equals("close button") || 
            d.equals("close video") || d.equals("dismiss") || d.contains("close ad") ||
            d.contains("close advertisement") || d.startsWith("close") ||
            d.contains("skip ad") || d.equals("skip ad")) {
            return true;
        }
"@
$content = $content.Replace($target3, $replace3)

# 4. Dialog interceptors: Cancel on Skip Milestone & Resume on Lose Reward
$target4 = @"
            // 2. OFFICIAL SKIP ARROW CHECK (Explicit skip >> arrows)
"@
$replace4 = @"
            // TOP PRIORITY: Intercept and dismiss ANY "Skip This Milestone?" dialog by tapping Cancel! NEVER skip milestones!
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                if (t.contains("skip this milestone") || d.contains("skip this milestone") ||
                    t.contains("skip milestone") || d.contains("skip milestone")) {
                    Log.w(TAG, "🚨 'Skip This Milestone?' dialog detected! Strictly clicking Cancel at (450, 953)...");
                    for (NodeItem cItem : allNodes) {
                        String ct = cItem.text.toLowerCase().trim();
                        if (ct.equals("cancel") || ct.contains("cancel")) {
                            tapNode(cItem);
                            lastTapTime = now + 1500;
                            return;
                        }
                    }
                    tapCoordinates(450, 953);
                    lastTapTime = now + 1500;
                    return;
                }
            }

            // TOP PRIORITY: Intercept AdMob "Close Ad? You will lose your reward" dialog and strictly tap RESUME!
            for (NodeItem item : allNodes) {
                String t = item.text.toLowerCase().trim();
                String d = item.contentDesc.toLowerCase().trim();
                if (t.contains("lose your reward") || d.contains("lose your reward") ||
                    (t.contains("close ad?") && !t.contains("quiz")) ||
                    t.contains("reward will be lost") || d.contains("reward will be lost")) {
                    Log.w(TAG, "🚨 'Close Ad? You will lose your reward' dialog detected! Strictly tapping RESUME...");
                    for (NodeItem rItem : allNodes) {
                        String rt = rItem.text.toLowerCase().trim();
                        if (rt.equals("resume") || rt.contains("resume") || rt.contains("keep watching")) {
                            tapNode(rItem);
                            lastTapTime = now + 1500;
                            return;
                        }
                    }
                    tapCoordinates(670, 910);
                    lastTapTime = now + 1500;
                    return;
                }
            }

            // 2. OFFICIAL SKIP ARROW CHECK (Explicit skip >> arrows)
"@
$content = $content.Replace($target4, $replace4)

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
Write-Host "Patched successfully!"
