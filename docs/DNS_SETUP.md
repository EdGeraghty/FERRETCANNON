# DNS Configuration Guide for areweyolosynapseyet.roflcopter.wtf

This guide explains how to configure DNS records to point your custom domain to the GitHub Pages site.

## Prerequisites

- Access to DNS management for `roflcopter.wtf` domain
- GitHub Pages is enabled for this repository (automatically done by the workflow)
- CNAME file is present in the `docs/` directory (already configured)

## DNS Configuration Options

You have two options for configuring DNS. Choose one:

### Option 1: CNAME Record (Recommended)

This is the recommended approach as it automatically updates if GitHub changes their IP addresses.

```
Type:  CNAME
Name:  areweyolosynapseyet
Value: edgeraghty.github.io.
TTL:   3600 (or automatic)
```

**Important:** 
- The Name should be just `areweyolosynapseyet` (not the full domain)
- The Value should end with a dot (`.`) if your DNS provider requires it
- Some DNS providers automatically append the root domain

### Option 2: A Records

If CNAME doesn't work (e.g., DNS provider restrictions), use A records pointing to GitHub's IP addresses:

```
Type:  A
Name:  areweyolosynapseyet
Value: 185.199.108.153
TTL:   3600

Type:  A
Name:  areweyolosynapseyet
Value: 185.199.109.153
TTL:   3600

Type:  A
Name:  areweyolosynapseyet
Value: 185.199.110.153
TTL:   3600

Type:  A
Name:  areweyolosynapseyet
Value: 185.199.111.153
TTL:   3600
```

**Note:** These IP addresses are current as of October 2025. If they change, check [GitHub's documentation](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site).

## Verification Steps

After configuring DNS:

1. **Wait for DNS Propagation** (can take 5 minutes to 48 hours)
   ```bash
   # Check DNS propagation (Linux/Mac)
   dig areweyolosynapseyet.roflcopter.wtf
   
   # Or use online tools
   # https://www.whatsmydns.net/
   ```

2. **Verify the Site is Live**
   ```bash
   curl -I https://areweyolosynapseyet.roflcopter.wtf
   ```
   
   You should see a `200 OK` response.

3. **Check HTTPS Certificate**
   - GitHub Pages automatically provisions an HTTPS certificate
   - This may take a few minutes after DNS propagation
   - Visit: https://areweyolosynapseyet.roflcopter.wtf

## Common DNS Provider Examples

### Cloudflare
1. Log in to Cloudflare dashboard
2. Select your domain `roflcopter.wtf`
3. Go to DNS → Records
4. Click "Add record"
5. Set:
   - Type: `CNAME`
   - Name: `areweyolosynapseyet`
   - Target: `edgeraghty.github.io`
   - Proxy status: DNS only (grey cloud) - Important!
   - TTL: Auto
6. Save

**Important:** Disable Cloudflare proxy (grey cloud, not orange) for GitHub Pages to work correctly.

### Google Domains / Cloud DNS
1. Go to DNS settings
2. Add custom record:
   - Host name: `areweyolosynapseyet`
   - Type: `CNAME`
   - TTL: `3600`
   - Data: `edgeraghty.github.io.`

### AWS Route 53
1. Go to your hosted zone for `roflcopter.wtf`
2. Create record:
   - Record name: `areweyolosynapseyet`
   - Record type: `CNAME`
   - Value: `edgeraghty.github.io`
   - Routing policy: Simple routing
   - TTL: 300

### Namecheap
1. Go to Domain List → Manage
2. Advanced DNS tab
3. Add New Record:
   - Type: `CNAME Record`
   - Host: `areweyolosynapseyet`
   - Value: `edgeraghty.github.io.`
   - TTL: Automatic

## Troubleshooting

### Site not loading after DNS configuration

1. **Check DNS propagation:**
   ```bash
   nslookup areweyolosynapseyet.roflcopter.wtf
   ```
   Should return `edgeraghty.github.io` (CNAME) or GitHub's IPs (A records)

2. **Verify CNAME file in repository:**
   - File location: `docs/CNAME`
   - Content: `areweyolosynapseyet.roflcopter.wtf`
   - No extra whitespace or line breaks

3. **Check GitHub Pages settings:**
   - Go to: https://github.com/EdGeraghty/FERRETCANNON/settings/pages
   - Custom domain should show: `areweyolosynapseyet.roflcopter.wtf`
   - "Enforce HTTPS" should be enabled (after DNS propagates)

### Certificate / HTTPS errors

- GitHub Pages provisions HTTPS certificates automatically
- This can take 10-60 minutes after DNS propagation
- If it takes longer, try:
  1. Remove the custom domain in GitHub settings
  2. Wait 5 minutes
  3. Re-add the custom domain
  4. Wait for certificate provisioning

### Wrong content showing

- Clear browser cache
- Wait for GitHub Pages deployment (check Actions tab)
- Verify the correct branch is configured (should be `main` with `/docs` folder)

## References

- [GitHub Pages Custom Domain Documentation](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site)
- [GitHub Pages IP Addresses](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site#configuring-an-apex-domain)
- [DNS Propagation Checker](https://www.whatsmydns.net/)

## Testing the Dashboard

Once DNS is configured and the site is live, visit:

```
https://areweyolosynapseyet.roflcopter.wtf
```

You should see:
- 🎯 Are We Yolo Synapse Yet? header
- Overall compliance percentage
- Detailed test suite results
- Auto-refresh every 5 minutes

The dashboard fetches live data from:
```
https://raw.githubusercontent.com/EdGeraghty/FERRETCANNON/complement-badges/test-results.json
```

This data is automatically updated by the [Complement parallel workflow](../.github/workflows/complement-parallel.yml).
