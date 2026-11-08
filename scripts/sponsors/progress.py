#!/usr/bin/env python3
"""Draw the README's sponsor progress bar: how much sponsors have given towards a goal.

GitHub Sponsors goals only count monthly sponsorships, so this adds up every sponsor's
lifetime amount (one-time and monthly) instead. Only totals are read: no names, so private
sponsors stay private.

  progress.py OUT.svg                     reads the total with $SPONSORS_TOKEN (the sponsored
                                          account's own token: GitHub only tells it)
  progress.py OUT.svg --raised-cents N    draws a given total (tests, previews)
"""
import argparse
import json
import os
import sys
import urllib.request
from xml.sax.saxutils import escape

GOAL_CENTS = 12500
PURPOSE = 'Apple Developer + Google Play accounts'
QUERY = '''query($after: String) { viewer { lifetimeReceivedSponsorshipValues(first: 100, after: $after) {
  nodes { amountInCents } pageInfo { hasNextPage endCursor } } } }'''


def total_cents(pages):
    """Every sponsor's lifetime amount, added up across the result pages."""
    return sum(node['amountInCents'] for page in pages for node in page['nodes'])


def fetch_pages(token):
    after = None
    while True:
        body = json.dumps({'query': QUERY, 'variables': {'after': after}}).encode()
        request = urllib.request.Request('https://api.github.com/graphql', data=body, headers={
            'Authorization': f'bearer {token}', 'Content-Type': 'application/json', 'User-Agent': 'silage-sponsor-progress'})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.load(response)
        if data.get('errors'):
            raise RuntimeError(f"GitHub refused the query: {data['errors'][0].get('message')}")
        page = data['data']['viewer']['lifetimeReceivedSponsorshipValues']
        yield page
        if not page['pageInfo']['hasNextPage']:
            return
        after = page['pageInfo']['endCursor']


def dollars(cents):
    return f'${cents // 100}' if cents % 100 == 0 else f'${cents / 100:.2f}'


def render_svg(raised_cents, goal_cents=GOAL_CENTS, purpose=PURPOSE):
    """A 460×86 card: '$X of $GOAL raised', what it's for, and a bar filled up to 100%."""
    fraction = min(1.0, max(0.0, raised_cents / goal_cents)) if goal_cents > 0 else 0.0
    bar = 420
    fill = round(bar * fraction, 1)
    headline = escape(f'{dollars(raised_cents)} of {dollars(goal_cents)} raised')
    font = 'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif"'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="460" height="86" viewBox="0 0 460 86" role="img" aria-label="{headline}">
  <rect width="460" height="86" rx="10" fill="#0d1117"/>
  <text x="20" y="30" fill="#f0f6fc" {font} font-size="16" font-weight="600">{headline}</text>
  <text x="440" y="30" fill="#8b949e" {font} font-size="14" text-anchor="end">{round(fraction * 100)}%</text>
  <text x="20" y="50" fill="#8b949e" {font} font-size="12">{escape(purpose)}</text>
  <rect x="20" y="62" width="{bar}" height="10" rx="5" fill="#30363d"/>
  <rect x="20" y="62" width="{fill}" height="10" rx="5" fill="#db61a2"/>
</svg>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out')
    parser.add_argument('--raised-cents', type=int)
    args = parser.parse_args()
    if args.raised_cents is None:
        token = os.environ.get('SPONSORS_TOKEN')
        if not token:
            sys.exit('SPONSORS_TOKEN is not set')
        raised = total_cents(fetch_pages(token))
    else:
        raised = args.raised_cents
    with open(args.out, 'w', encoding='utf-8') as out:
        out.write(render_svg(raised))
    print(f'{dollars(raised)} of {dollars(GOAL_CENTS)} raised')


if __name__ == '__main__':
    main()
