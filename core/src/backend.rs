use reqwest::header::{AUTHORIZATION, CONTENT_ENCODING, CONTENT_TYPE, HeaderMap, HeaderName, HeaderValue};
use std::time::Duration;

use anyhow::{Context, Result, anyhow};

pub struct BackendHeaders<'a> {
    pub authorization: &'a str,
    pub plugin_version: &'a str,
    pub ide_name: &'a str,
    pub ide_version: &'a str,
    pub debug_info: &'a str,
    pub content_encoding: Option<&'a str>,
}

fn build_headers(headers: &BackendHeaders<'_>) -> Result<HeaderMap> {
    let mut map = HeaderMap::new();
    map.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    map.insert(
        AUTHORIZATION,
        HeaderValue::from_str(headers.authorization).context("invalid Authorization header")?,
    );
    map.insert(
        HeaderName::from_static("x-plugin-version"),
        HeaderValue::from_str(headers.plugin_version).context("invalid X-Plugin-Version header")?,
    );
    map.insert(
        HeaderName::from_static("x-ide-name"),
        HeaderValue::from_str(headers.ide_name).context("invalid X-IDE-Name header")?,
    );
    map.insert(
        HeaderName::from_static("x-ide-version"),
        HeaderValue::from_str(headers.ide_version).context("invalid X-IDE-Version header")?,
    );
    map.insert(
        HeaderName::from_static("x-debug-info"),
        HeaderValue::from_str(headers.debug_info).context("invalid X-Debug-Info header")?,
    );
    if let Some(content_encoding) = headers.content_encoding {
        map.insert(
            CONTENT_ENCODING,
            HeaderValue::from_str(content_encoding).context("invalid Content-Encoding header")?,
        );
    }
    Ok(map)
}

pub async fn post_backend_bytes(
    url: &str,
    body: Vec<u8>,
    headers: BackendHeaders<'_>,
    timeout: Duration,
) -> Result<String> {
    let client = reqwest::Client::builder().timeout(timeout).build()?;
    let response = client
        .post(url)
        .headers(build_headers(&headers)?)
        .body(body)
        .send()
        .await
        .with_context(|| format!("request failed: {url}"))?;

    let status = response.status();
    let text = response.text().await.context("failed to read response body")?;
    if !status.is_success() {
        return Err(anyhow!("HTTP {status}: {text}"));
    }
    Ok(text)
}

pub async fn get_backend_text(
    url: &str,
    authorization: &str,
    timeout: Duration,
) -> Result<Option<String>> {
    let client = reqwest::Client::builder().timeout(timeout).build()?;
    let response = client
        .get(url)
        .header(
            AUTHORIZATION,
            HeaderValue::from_str(authorization).context("invalid Authorization header")?,
        )
        .send()
        .await
        .with_context(|| format!("request failed: {url}"))?;

    if !response.status().is_success() {
        return Ok(None);
    }

    Ok(Some(response.text().await.context("failed to read response body")?))
}
