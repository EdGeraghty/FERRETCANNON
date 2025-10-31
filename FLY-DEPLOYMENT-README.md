# FERRETCANNON Matrix Server - Fly.io Deployment Guide

This guide will help you deploy your FERRETCANNON Matrix server to fly.io.

## Prerequisites

1. **fly.io Account**: Sign up at [fly.io](https://fly.io)
2. **flyctl CLI**: Install from [fly.io/docs/flyctl/install/](https://fly.io/docs/flyctl/install/)
3. **Docker**: Ensure Docker is installed and running

## Quick Deployment

### Windows
```bash
# Run the deployment script
deploy-to-fly.bat
```

### Linux/Mac
```bash
# Make the script executable
chmod +x deploy-to-fly.sh

# Run the deployment script
./deploy-to-fly.sh
```

## Manual Deployment Steps

### 1. Authenticate with fly.io
```bash
fly auth login
```

### 2. Initialize the App
```bash
fly launch --copy-config
```
This will:
- Create a `fly.toml` configuration file
- Set up the app with the configuration from `fly.toml`
- Ask you to choose a region and app name

### 3. Deploy
```bash
fly deploy
```

### 4. Check Status
```bash
fly status
fly open  # Opens your app in the browser
```

## Configuration

### Production Configuration
The `config.prod.yml` file contains production-ready settings. **Important changes to make:**

1. **Server Name**: Update `federation.serverName` with your actual fly.io domain
2. **JWT Secret**: Change `security.jwtSecret` to a secure random value
3. **CORS Origins**: Configure `server.corsAllowedOrigins` for your domain
4. **Rate Limiting**: Adjust `security.rateLimitRequests` as needed

### Database Persistence
- SQLite database is stored in a persistent volume at `/data/ferretcannon.db`
- Data persists between deployments
- Media files are stored in the container (consider using external storage for production)

## Fly.io Configuration

The `fly.toml` file includes:
- **Region**: `iad` (Washington DC)
- **VM Size**: 1 CPU, 1024MB RAM (shared CPU)
- **Persistent Volume**: 1GB for database storage
- **Health Checks**: Automatic health monitoring
- **Auto-scaling**: Scales to zero when not in use

## Management Commands

```bash
# View logs
fly logs

# Scale the app
fly scale count 2

# Update environment variables
fly secrets set MY_SECRET=value

# Check app info
fly info

# Destroy the app
fly apps destroy your-app-name
```

## Troubleshooting

### Build Issues
- Ensure Docker is running
- Check that all dependencies are available
- Verify the Dockerfile syntax

### Runtime Issues
```bash
# Check logs for errors
fly logs

# Restart the app
fly apps restart your-app-name

# Check app status
fly status
```

### Database Issues
- Database is automatically backed up to persistent volume
- If you need to reset: `fly volumes destroy <volume-id>`

## Production Considerations

1. **Security**:
   - Change default JWT secret
   - Configure proper CORS origins
   - Enable rate limiting
   - Use HTTPS (automatically enabled by fly.io)

2. **Performance**:
   - Monitor resource usage with `fly status`
   - Scale up if needed: `fly scale memory 2048`
   - Consider upgrading to dedicated CPU: `fly scale vm shared-cpu-1x`

3. **Backup**:
   - Database is automatically persisted
   - Consider regular backups of the volume
   - Media files are not persisted (consider external storage)

4. **Federation**:
   - Update server name in config
   - Ensure federation port is accessible
   - Configure allowed servers for security

## Cost Estimation

- **Free Tier**: ~$0/month (limited usage)
- **Paid Tier**: ~$5-10/month for basic usage
- **Storage**: ~$0.15/GB/month for persistent volume

## Support

- [Fly.io Documentation](https://fly.io/docs)
- [Matrix Specification](https://spec.matrix.org)
- [FERRETCANNON Issues](https://github.com/EdGeraghty/FERRETCANNON/issues)

## Files Created

- `Dockerfile` - Container configuration
- `fly.toml` - Fly.io app configuration
- `.dockerignore` - Docker build exclusions
- `config.prod.yml` - Production configuration
- `deploy-to-fly.bat` / `deploy-to-fly.sh` - Deployment scripts
