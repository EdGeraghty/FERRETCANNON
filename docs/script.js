// Configuration
const REPO_OWNER = 'EdGeraghty';
const REPO_NAME = 'FERRETCANNON';
const BRANCH = 'complement-badges';
const DATA_FILE = 'test-results.json';

// Suite name mappings for better display
const SUITE_NAMES = {
    'authentication': 'Authentication & Registration',
    'rooms': 'Room Operations',
    'sync': 'Sync & Presence',
    'federation': 'Federation',
    'media': 'Media & Content',
    'keys': 'Keys & Crypto',
    'knocking-restricted': 'Knocking & Restricted Rooms',
    'relations-threads': 'Event Relations & Threads',
    'moderation': 'Moderation',
    'additional': 'Additional Features',
    'events-history': 'Event & History',
    'join-membership': 'Join & Membership',
    'edge-cases': 'Edge Cases & Validation',
    'msc-experimental': 'MSC & Experimental Features',
    'miscellaneous': 'Miscellaneous'
};

// Fetch test results from the complement-badges branch
async function fetchTestResults() {
    try {
        const url = `https://raw.githubusercontent.com/${REPO_OWNER}/${REPO_NAME}/${BRANCH}/${DATA_FILE}`;
        const response = await fetch(url);
        
        if (!response.ok) {
            throw new Error(`Failed to fetch test results: ${response.status} ${response.statusText}`);
        }
        
        const data = await response.json();
        return data;
    } catch (error) {
        console.error('Error fetching test results:', error);
        throw error;
    }
}

// Get colour class based on percentage
function getColorClass(percentage) {
    if (percentage >= 90) return 'excellent';
    if (percentage >= 75) return 'good';
    if (percentage >= 60) return 'okay';
    if (percentage >= 40) return 'fair';
    if (percentage >= 20) return 'poor';
    return 'bad';
}

// Format suite name
function formatSuiteName(suiteKey) {
    return SUITE_NAMES[suiteKey] || suiteKey
        .split('-')
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ');
}

// Display overall stats
function displayOverallStats(overall) {
    document.getElementById('overall-percentage').textContent = `${overall.percentage}%`;
    document.getElementById('overall-passed').textContent = overall.passed;
    document.getElementById('overall-failed').textContent = overall.failed;
    document.getElementById('overall-skipped').textContent = overall.skipped;
    document.getElementById('overall-total').textContent = overall.total;
    
    const card = document.getElementById('overall-card');
    const colorClass = getColorClass(overall.percentage);
    card.classList.add(colorClass);
}

// Display suite cards
function displaySuites(suites) {
    const container = document.getElementById('suites-container');
    container.innerHTML = '';
    
    // Sort suites by name
    const sortedSuites = Object.entries(suites).sort((a, b) => {
        const nameA = formatSuiteName(a[0]);
        const nameB = formatSuiteName(b[0]);
        return nameA.localeCompare(nameB);
    });
    
    sortedSuites.forEach(([suiteKey, suite]) => {
        const card = document.createElement('div');
        card.className = 'suite-card';
        
        const percentage = document.createElement('div');
        percentage.className = `suite-percentage ${getColorClass(suite.percentage)}`;
        percentage.textContent = `${suite.percentage}%`;
        
        const name = document.createElement('div');
        name.className = 'suite-name';
        name.textContent = formatSuiteName(suiteKey);
        
        const stats = document.createElement('div');
        stats.className = 'suite-stats';
        stats.innerHTML = `
            <div class="suite-stat">
                <span class="suite-stat-label">Passed</span>
                <span class="suite-stat-value passed">${suite.passed}</span>
            </div>
            <div class="suite-stat">
                <span class="suite-stat-label">Failed</span>
                <span class="suite-stat-value failed">${suite.failed}</span>
            </div>
            <div class="suite-stat">
                <span class="suite-stat-label">Skipped</span>
                <span class="suite-stat-value skipped">${suite.skipped}</span>
            </div>
            <div class="suite-stat">
                <span class="suite-stat-label">Total</span>
                <span class="suite-stat-value">${suite.total}</span>
            </div>
        `;
        
        card.appendChild(name);
        card.appendChild(percentage);
        card.appendChild(stats);
        container.appendChild(card);
    });
}

// Update last updated timestamp
function updateLastUpdated() {
    const now = new Date();
    const formatted = now.toLocaleString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        timeZoneName: 'short'
    });
    document.getElementById('last-update').textContent = formatted;
}

// Show error message
function showError(message) {
    document.getElementById('loading').classList.add('hidden');
    document.getElementById('results').classList.add('hidden');
    document.getElementById('error').classList.remove('hidden');
    document.getElementById('error-message').textContent = message;
}

// Show results
function showResults() {
    document.getElementById('loading').classList.add('hidden');
    document.getElementById('error').classList.add('hidden');
    document.getElementById('results').classList.remove('hidden');
}

// Main initialization
async function init() {
    try {
        const data = await fetchTestResults();
        
        if (!data || !data.overall || !data.suites) {
            throw new Error('Invalid data format received from server');
        }
        
        displayOverallStats(data.overall);
        displaySuites(data.suites);
        updateLastUpdated();
        showResults();
        
        console.log('Test results loaded successfully:', data);
    } catch (error) {
        console.error('Failed to initialize dashboard:', error);
        showError(`Unable to load test results. ${error.message}`);
    }
}

// Auto-refresh every 5 minutes
setInterval(init, 5 * 60 * 1000);

// Initialize on page load
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
