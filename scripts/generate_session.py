#!/usr/bin/env python3
"""
BobStream Telegram Session Generator
Run this once on your local computer to generate your Telethon StringSession.
"""
from telethon.sync import TelegramClient
from telethon.sessions import StringSession

# Your Telegram Credentials
API_ID = 33673228
API_HASH = "b86b954873058e3766fe17187ce87fd6"

print("=" * 60)
print("BobStream Telethon StringSession Generator")
print("=" * 60)
print("Enter your phone number with country code when prompted (e.g., +91XXXXXXXXXX).")
print("Then enter the Telegram OTP login code you receive.")
print("-" * 60)

with TelegramClient(StringSession(), API_ID, API_HASH) as client:
    session_string = client.session.save()
    print("\n" + "=" * 60)
    print("SUCCESS! Here is your STRING_SESSION string:")
    print("=" * 60)
    print(session_string)
    print("=" * 60)
    print("Copy the entire line above and add it as STRING_SESSION in GitHub Secrets.")
