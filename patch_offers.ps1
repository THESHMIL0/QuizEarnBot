$path = "C:\Users\THESHMIL\Desktop\Quiz_Earn_Bot\android\src\main\java\com\quizearn\assistant\QuizEarnAccessibilityService.java"
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)

$target = @"
            // Third: Check if any offer card shows "all milestones completed"
"@

$replacement = @"
            // 3a: Actionable app offer card with pending usage or ready to open!
            for (NodeItem item : allNodes) {
                String t = item.text.trim().toLowerCase();
                String d = item.contentDesc.trim().toLowerCase();
                if (t.contains("use app for") || d.contains("use app for") ||
                    t.contains("tap \"open app\"") || d.contains("tap \"open app\"")) {
                    Log.i(TAG, "⚡ Actionable app offer card found on My Super Offers! Tapping: " + item.text + " at " + item.rect);
                    tapNode(item);
                    lastTapTime = now + 2000;
                    lastProgressTime = now;
                    currentStatus = "Opening Offer Card...";
                    return true;
                }
            }

            // Third: Check if any offer card shows "all milestones completed"
"@

if ($content.Contains($target)) {
    $content = $content.Replace($target, $replacement)
    [System.IO.File]::WriteAllText($path, $content, [System.Text.Encoding]::UTF8)
    Write-Host "Replaced successfully!"
} else {
    Write-Host "Target not found!"
}
