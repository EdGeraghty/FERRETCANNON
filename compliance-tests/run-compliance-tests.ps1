#!/usr/bin/env pwsh
#Requires -Version 5.1

<#
.SYNOPSIS
    FERRETCANNON Matrix Compliance Test Suite Runner
    
.DESCRIPTION
    Runs compliance tests to verify FERRETCANNON's implementation against the Matrix Specification v1.16.
    Big shoutout to the FERRETCANNON massive for making this happen! 🎆
    
.PARAMETER Suite
    Specific test suite to run: canonical-json, event-hashing, event-signing, federation, or all
    
.PARAMETER Verbose
    Enable verbose output with detailed test information
    
.PARAMETER OutputFormat
    Output format: text, json, or junit
    
.EXAMPLE
    .\run-compliance-tests.ps1
    Runs all compliance tests
    
.EXAMPLE
    .\run-compliance-tests.ps1 -Suite canonical-json -Verbose
    Runs only canonical JSON tests with verbose output
    
.EXAMPLE
    .\run-compliance-tests.ps1 -OutputFormat json > results.json
    Runs all tests and outputs results as JSON
#>

param(
    [Parameter()]
    [ValidateSet('all', 'canonical-json', 'event-hashing', 'event-signing', 'federation')]
    [string]$Suite = 'all',
    
    [Parameter()]
    [switch]$VerboseOutput,
    
    [Parameter()]
    [ValidateSet('text', 'json', 'junit')]
    [string]$OutputFormat = 'text',
    
    [Parameter()]
    [string]$ServerUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$testResults = @{
    Total = 0
    Passed = 0
    Failed = 0
    Skipped = 0
    Tests = @()
    Duration = 0
}

function Write-TestHeader {
    param([string]$Message)
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host " $Message" -ForegroundColor Cyan
    Write-Host "========================================`n" -ForegroundColor Cyan
}

function Write-TestResult {
    param(
        [string]$TestName,
        [bool]$Passed,
        [string]$Message = "",
        [string]$Expected = "",
        [string]$Actual = ""
    )
    
    if ($Passed) {
        Write-Host "✅ PASS: $TestName" -ForegroundColor Green
    } else {
        Write-Host "❌ FAIL: $TestName" -ForegroundColor Red
        if ($Message) {
            Write-Host "   Error: $Message" -ForegroundColor Yellow
        }
        if ($Expected) {
            Write-Host "   Expected: $Expected" -ForegroundColor Gray
        }
        if ($Actual) {
            Write-Host "   Actual:   $Actual" -ForegroundColor Gray
        }
    }
    
    $testResults.Total++
    if ($Passed) {
        $testResults.Passed++
    } else {
        $testResults.Failed++
    }
    
    $testResults.Tests += @{
        Name = $TestName
        Passed = $Passed
        Message = $Message
        Expected = $Expected
        Actual = $Actual
    }
}

function Test-CanonicalJSON {
    Write-TestHeader "Canonical JSON Tests"
    
    $testData = Get-Content ".\test-data\canonical-json.json" -Raw | ConvertFrom-Json
    
    foreach ($test in $testData.tests) {
        if ($VerboseOutput) {
            Write-Host "Running: $($test.name)" -ForegroundColor Gray
        }
        
        try {
            # Call our Kotlin implementation via HTTP endpoint
            $response = Invoke-RestMethod -Uri "$ServerUrl/_matrix/test/canonical-json" `
                -Method POST `
                -Body ($test.input | ConvertTo-Json -Depth 10 -Compress) `
                -ContentType "application/json" `
                -ErrorAction Stop
            
            $actual = $response.canonical_json
            $expected = $test.expected
            
            if ($actual -eq $expected) {
                Write-TestResult -TestName $test.name -Passed $true
            } else {
                Write-TestResult -TestName $test.name -Passed $false `
                    -Message "Canonical JSON mismatch" `
                    -Expected $expected `
                    -Actual $actual
            }
        } catch {
            Write-TestResult -TestName $test.name -Passed $false `
                -Message "Test execution failed: $($_.Exception.Message)"
        }
    }
}

function Test-EventHashing {
    Write-TestHeader "Event Content Hash Tests"
    
    $testData = Get-Content ".\test-data\event-hashing.json" -Raw | ConvertFrom-Json
    
    foreach ($test in $testData.tests) {
        if ($VerboseOutput) {
            Write-Host "Running: $($test.name)" -ForegroundColor Gray
        }
        
        try {
            # Call our Kotlin implementation via HTTP endpoint
            $response = Invoke-RestMethod -Uri "$ServerUrl/_matrix/test/compute-hash" `
                -Method POST `
                -Body ($test.event | ConvertTo-Json -Depth 10 -Compress) `
                -ContentType "application/json" `
                -ErrorAction Stop
            
            if ($test.expected_canonical_json) {
                $actualCanonical = $response.canonical_json
                $expectedCanonical = $test.expected_canonical_json
                
                if ($actualCanonical -eq $expectedCanonical) {
                    Write-TestResult -TestName "$($test.name) - Canonical JSON" -Passed $true
                } else {
                    Write-TestResult -TestName "$($test.name) - Canonical JSON" -Passed $false `
                        -Message "Canonical JSON mismatch" `
                        -Expected $expectedCanonical `
                        -Actual $actualCanonical
                }
            }
            
            if ($test.expected_hash) {
                $actualHash = $response.hash
                $expectedHash = $test.expected_hash
                
                if ($actualHash -eq $expectedHash) {
                    Write-TestResult -TestName "$($test.name) - Hash" -Passed $true
                } else {
                    Write-TestResult -TestName "$($test.name) - Hash" -Passed $false `
                        -Message "Hash mismatch" `
                        -Expected $expectedHash `
                        -Actual $actualHash
                }
            }
            
            if ($test.note -and $VerboseOutput) {
                Write-Host "   Note: $($test.note)" -ForegroundColor Cyan
            }
        } catch {
            Write-TestResult -TestName $test.name -Passed $false `
                -Message "Test execution failed: $($_.Exception.Message)"
        }
    }
}

function Test-EventSigning {
    Write-TestHeader "Event Signing Tests"
    
    Write-Host "Event signing tests not yet implemented" -ForegroundColor Yellow
    Write-Host "Coming soon with test vectors from Matrix spec!" -ForegroundColor Yellow
}

function Test-Federation {
    Write-TestHeader "Federation Tests"
    
    Write-Host "Federation tests not yet implemented" -ForegroundColor Yellow
    Write-Host "Coming soon with real federation scenarios!" -ForegroundColor Yellow
}

function Show-Summary {
    Write-Host "`n========================================" -ForegroundColor Magenta
    Write-Host " Test Summary" -ForegroundColor Magenta
    Write-Host "========================================" -ForegroundColor Magenta
    Write-Host "Total:   $($testResults.Total)" -ForegroundColor White
    Write-Host "Passed:  $($testResults.Passed)" -ForegroundColor Green
    Write-Host "Failed:  $($testResults.Failed)" -ForegroundColor Red
    Write-Host "Skipped: $($testResults.Skipped)" -ForegroundColor Yellow
    
    $passRate = if ($testResults.Total -gt 0) { 
        [math]::Round(($testResults.Passed / $testResults.Total) * 100, 2) 
    } else { 
        0 
    }
    Write-Host "Pass Rate: $passRate%" -ForegroundColor $(if ($passRate -eq 100) { 'Green' } elseif ($passRate -ge 80) { 'Yellow' } else { 'Red' })
    
    Write-Host "`n🎆 Big shoutout to the FERRETCANNON massive! 🎆" -ForegroundColor Cyan
}

function Export-Results {
    param([string]$Format)
    
    switch ($Format) {
        'json' {
            $testResults | ConvertTo-Json -Depth 10
        }
        'junit' {
            # TODO: Implement JUnit XML format
            Write-Warning "JUnit format not yet implemented"
        }
    }
}

# Main execution
try {
    Write-Host @"
╔═══════════════════════════════════════════════════════════╗
║                                                           ║
║   FERRETCANNON Matrix Compliance Test Suite              ║
║   Testing against Matrix Specification v1.16             ║
║                                                           ║
║   🎆 For the FERRETCANNON massive! 🎆                      ║
║                                                           ║
╚═══════════════════════════════════════════════════════════╝
"@ -ForegroundColor Cyan

    Write-Host "`nServer URL: $ServerUrl" -ForegroundColor Gray
    Write-Host "Test Suite: $Suite" -ForegroundColor Gray
    Write-Host ""
    
    $startTime = Get-Date
    
    # Check if server is running
    try {
        $null = Invoke-WebRequest -Uri "$ServerUrl/_matrix/server-info" -Method GET -UseBasicParsing -TimeoutSec 5
    } catch {
        Write-Host "⚠️  Warning: Could not connect to server at $ServerUrl" -ForegroundColor Yellow
        Write-Host "   Some tests may fail. Make sure FERRETCANNON is running!" -ForegroundColor Yellow
        Write-Host ""
    }
    
    # Run requested test suites
    switch ($Suite) {
        'all' {
            Test-CanonicalJSON
            Test-EventHashing
            Test-EventSigning
            Test-Federation
        }
        'canonical-json' { Test-CanonicalJSON }
        'event-hashing' { Test-EventHashing }
        'event-signing' { Test-EventSigning }
        'federation' { Test-Federation }
    }
    
    $endTime = Get-Date
    $testResults.Duration = ($endTime - $startTime).TotalSeconds
    
    if ($OutputFormat -eq 'text') {
        Show-Summary
    } else {
        Export-Results -Format $OutputFormat
    }
    
    # Exit with appropriate code
    exit $(if ($testResults.Failed -eq 0) { 0 } else { 1 })
    
} catch {
    Write-Host "`n❌ Fatal error running tests: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor Gray
    exit 1
}
