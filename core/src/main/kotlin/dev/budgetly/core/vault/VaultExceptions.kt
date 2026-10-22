package dev.budgetly.core.vault

class VaultLockedException : RuntimeException("Le coffre est verrouillé")

class InvalidPasswordException : RuntimeException("Mot de passe incorrect")

class VaultAlreadyInitializedException : RuntimeException("Le coffre existe déjà")

class VaultNotInitializedException : RuntimeException("Aucun coffre : créez d'abord un mot de passe maître")

class WeakPasswordException(minLength: Int) :
    RuntimeException("Le mot de passe maître doit contenir au moins $minLength caractères")

class OrphanDatabaseException :
    RuntimeException("Une base existe sans paramètres de coffre (vault.json). Supprimez-la ou restaurez vault.json.")
