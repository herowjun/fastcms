$token = (Get-Content f:\wangjun\ideaProjects\fastcms\.tmp-verify\token.txt -Raw).Trim()
$headers = @{Authorization = "Bearer $token" }
$deadline = (Get-Date).AddMinutes(25)
$lastStatus = ""

function Get-Body($r) {
    if ($r.Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($r.Content) }
    return "$($r.Content)"
}

while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:8080/fastcms/api/admin/plugin/list?pageNum=1&pageSize=50" -Headers $headers -UseBasicParsing -TimeoutSec 10
        $body = Get-Body $r
        if ($body -match 'article-skills-plugin') {
            Write-Output "NEW-INSTANCE-READY"
            exit 0
        }
        $newStatus = "old-instance (plugin absent, HTTP $($r.StatusCode))"
        if ($newStatus -ne $lastStatus) { Write-Output $newStatus; $lastStatus = $newStatus }
    } catch {
        $msg = $_.Exception.Message
        if ($msg -match '拒绝|refused|Unable to connect|无法连接') {
            if ($lastStatus -ne "down") { Write-Output "instance down (restarting...)"; $lastStatus = "down" }
        } else {
            $code = "?"
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            $newStatus = "HTTP ${code}: $msg"
            if ($newStatus -ne $lastStatus) { Write-Output $newStatus; $lastStatus = $newStatus }
        }
    }
    Start-Sleep -Seconds 20
}
Write-Output "TIMEOUT-25MIN"
exit 1
