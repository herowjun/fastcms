$token = (Get-Content f:\wangjun\ideaProjects\fastcms\.tmp-verify\token.txt -Raw).Trim()
$headers = @{Authorization = "Bearer $token" }
$base = "http://localhost:8080/fastcms/api"

function Get-Api($url) {
    try {
        $r = Invoke-WebRequest -Uri "$base$url" -Headers $headers -UseBasicParsing -TimeoutSec 15
        $body = [System.Text.Encoding]::UTF8.GetString($r.Content)
        return "HTTP $($r.StatusCode): $body"
    } catch {
        $code = "?"
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return "HTTP $code ERR: $($_.Exception.Message)"
    }
}

Write-Output "===== 1. 插件列表（含 tags）====="
Write-Output (Get-Api "/admin/plugin/list?pageNum=1&pageSize=20")

Write-Output "`n===== 2. 插件资产 article-skills-plugin ====="
Write-Output (Get-Api "/admin/plugin/assets/article-skills-plugin")

Write-Output "`n===== 3. skill 聚合清单 ====="
Write-Output (Get-Api "/admin/ai/agent/skills")

Write-Output "`n===== 4. skill 正文 article-skills-plugin/article-topic ====="
Write-Output (Get-Api "/admin/ai/agent/skill-content?skillId=article-skills-plugin%2Farticle-topic")

Write-Output "`n===== 5. 智能体列表（内置文章智能体 skill 引用）====="
Write-Output (Get-Api "/admin/ai/agent/list")

Write-Output "`n===== 6. 回归：hello-world-plugin 免认证端点 ====="
try {
    $r = Invoke-WebRequest -Uri "http://localhost:8080/fastcms/plugin/hello/say" -UseBasicParsing -TimeoutSec 15
    Write-Output "HTTP $($r.StatusCode): $([System.Text.Encoding]::UTF8.GetString($r.Content))"
} catch {
    $code = "?"
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    Write-Output "HTTP $code ERR: $($_.Exception.Message)"
}
