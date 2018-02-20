# SSO-Cookie-Fetcher
Uses JBrowserDriver to go to website, do generic "log in steps" then print out the cookies after logging in.

See https://github.com/nddipiazza/SSO-Cookie-Fetcher/blob/master/input.json for an example of the input you need to send it.

# Running

`java -jar sso-cookie-fetcher.jar --url http://your-login-page.com [options] < cat /path/to/input.json`

## Options:

 - `--url`                 required = true, desc = Starting URL
 - `--proxy`               required = false, desc = Proxy server in <host>:<args> format", default = NULL
 - `--screenshots`        required = false, desc = Save screenshots of each web form as sso process proceeds default = false
 - `--stopAfterAttempt`    required = false, desc = Stop retrying to get sso cookies after this many failed attempts, default = 5
 - `--attemptTimeLimiter`  required = false, desc = Limit attempts to this many seconds, default = 5 * 60 seconds
 - `--socketTimeout`       required = false, desc = Jbrowserdriver's socket timeout parameter, default = 10000ms
 - `--ajaxWait`       required = false, desc = Jbrowserdriver's ajax wait parameter, default = 10000ms
  
