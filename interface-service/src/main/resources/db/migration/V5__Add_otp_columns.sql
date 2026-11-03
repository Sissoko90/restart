-- Ajouter les colonnes OTP pour la vérification par code
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_otp_code VARCHAR(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_otp_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_otp_code VARCHAR(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_otp_expires_at TIMESTAMP;

-- Ajouter les colonnes OTP pour la réinitialisation de mot de passe
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_otp_code VARCHAR(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_reset_otp_expires_at TIMESTAMP;
