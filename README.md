# Telecom Mod

Simulation de reseaux de telecommunications dans Minecraft : cables cuivre/fibre, routeurs, serveurs, antennes multibandes, smartphone, tests de debit et supervision web.

La mise en oeuvre de [la roadmap](ROADMAP_REALISME_ET_OPTIMISATION.md) commence par le jalon J0 : compatibilite, securite, sauvegardes et tests. Le mod reste un prototype ; les chaines FTTH detaillees, operateurs, abonnements et protocoles avances ne sont pas encore implementes.

## Versions

| Composant | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| NeoForge | 21.11.42 |
| Java cible | 21 |
| Node.js pour reconstruire le dashboard | 22 recommande |

Le wrapper Gradle est inclus. Les donnees du graphe et les champs des block entities precedemment sauvegardes sont conserves par la migration d'API. Effectuer une sauvegarde du monde avant toute mise a jour : la migration de mondes complets issus d'anciennes versions de Minecraft n'est pas validee par les seuls tests NBT.

Si `telecom_network.dat` existe mais est illisible ou utilise un schema inconnu, le mod refuse de le remplacer par un graphe vide. L'erreur interrompt le chargement du reseau ; il faut examiner le fichier ou restaurer une sauvegarde avant de continuer.

## Construction

```sh
./gradlew build
```

Le JAR est produit dans `build/libs/`. Ce build utilise les ressources web deja embarquees. Apres une modification du dashboard, reconstruire ses ressources avant Gradle :

```sh
npm ci --prefix web-dashboard
npm --prefix web-dashboard run check
npm --prefix web-dashboard test
npm --prefix web-dashboard run build
./gradlew build
```

Le lockfile npm fixe les dependances. La CI reconstruit le dashboard avant le mod. Les sources `src/gametest/` sont reservees au developpement et ne sont pas incluses dans le JAR distribue.

## Developpement

```sh
./gradlew runClient
./gradlew runServer
```

Ces configurations utilisent `run/`. Le serveur normal reste soumis a l'acceptation de l'EULA Minecraft par son administrateur. Les tests de monde utilisent un repertoire distinct `build/gametest/` et ne touchent pas aux sauvegardes de `run/saves/`.

## Premier reseau

1. Ouvrir un monde creatif et l'onglet Telecom.
2. Placer un serveur et un routeur, puis les relier par un chemin continu de fibre standard.
3. Attendre le recalcul du graphe ou utiliser `/telecom recalculate` avec les droits administrateur.
4. Ouvrir le routeur a proximite pour lancer un test de debit.
5. Pour le mobile, relier une antenne au reseau, ouvrir sa configuration et activer au moins une bande.
6. Utiliser un smartphone pour consulter la reception et lancer un test mobile.

Le test doit disposer d'un chemin jusqu'a un serveur. Les debits et l'identite reseau utilises sont calcules cote serveur. Un joueur ne peut lancer qu'un test manuel a la fois ; le graphe limite l'ensemble des sessions a 256. Les choix de duree existants concernent chaque phase de debit, pas la duree totale du test.

Les IP mobiles sont allouees par UUID, persistees par dimension dans une plage privee `172.16.0.0/12` et restent independantes de la position de l'antenne. L'adressage hierarchique du reseau fixe reste a remplacer par un IPAM stable.

## Dashboard et securite

Par defaut, le dashboard ecoute seulement sur `http://127.0.0.1:8080`. La consultation locale ne requiert pas de token ; toute action de speedtest requiert un token. Une ecoute non locale refuse de demarrer sans token et protege toutes les API de donnees.

| Configuration | Defaut | Role |
| --- | --- | --- |
| Propriete JVM `telecom.http.enabled` | `true` | Activer HTTP. |
| Propriete JVM `telecom.http.bind` | `127.0.0.1` | Adresse d'ecoute. |
| Propriete JVM `telecom.http.port` | `8080` | Port de 1 a 65535. |
| Propriete JVM `telecom.http.origins` | vide | Origines supplementaires exactes, separees par virgules, sans chemin ni wildcard. |
| Variable d'environnement `TELECOM_HTTP_TOKEN` | absente | Secret Bearer pour les actions et l'acces distant. |

Les proprietes `-Dtelecom.http.*` doivent etre passees a la JVM du jeu ou du serveur, pas seulement a la JVM Gradle. Le token est herite de l'environnement du processus. Utiliser un secret aleatoire robuste ; ne jamais le publier dans Git, les URLs ou les logs. Il est saisi dans le dashboard et conserve uniquement en memoire de page.

Pour le developpement web, `npm --prefix web-dashboard run dev` lance Vite sur loopback et proxifie `/api` vers le port 8080. Autoriser son origine exacte, par exemple `http://localhost:5173`, dans la JVM Minecraft. Une origine autorisee permet aussi son autorite HTTP pour les controles `Host`, notamment derriere un reverse proxy.

Pour un acces distant, utiliser HTTPS via reverse proxy ou tunnel securise. Aucun TLS ni systeme de roles operateurs n'est fourni dans ce premier lot. Les fichiers statiques restent accessibles pour permettre la saisie du token, sans donner acces aux donnees protegees.

### Limites operationnelles

- Requetes de mutation limitees a 4 Kio ; quatre workers HTTP et une file de 32 requetes.
- Un seul travail Minecraft issu du web a la fois ; attente de 750 ms et budget cooperatif de 10 ms pour les instantanes.
- Reponses `429` ou `503` lorsque les limites sont atteintes ; un timeout d'une mutation deja commencee peut laisser son resultat indetermine.
- Tuiles uniquement pour les chunks deja charges ; aucune generation de terrain par consultation de la carte.
- Cache serveur de 256 tuiles expirees apres 30 secondes ; cache navigateur borne a 1 024 entrees.
- Certains plafonds de noeuds restent indicatifs : l'unification de toutes les capacites et la congestion realiste appartiennent aux lots suivants.

## Tests

```sh
./gradlew test
./gradlew runGameTestServer
```

La suite JUnit couvre graphe/NBT, sessions, baux mobiles, codecs, ressources d'items, enregistrement du mod, block entities et HTTP. Le serveur ephemere JUnit ne cree pas de monde ; les interactions de blocs sont donc verifiees separement par GameTest.

Pour exercer aussi les chemins HTTP authentifies avec un token reserve aux tests :

```sh
TELECOM_HTTP_TOKEN=local-test-token ./gradlew test --rerun
```

Pour verifier le comportement sans token :

```sh
env -u TELECOM_HTTP_TOKEN ./gradlew test --rerun --tests '*TelecomHttpServerTest'
```

Deux tests HTTP authentifies sont ignores si aucun token n'est defini. Les tests HTTP utilisent de vraies connexions locales et des objets Minecraft simules ; ils ne remplacent pas une recette interactive multijoueur. Les benchmarks Ville/Stress et la verification visuelle complete restent a realiser.

## References et licence

- [Documentation NeoForge](https://docs.neoforged.net/docs/1.21.11/)
- [Conditions des mappings Mojang](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)
- Licence declaree du mod : `All Rights Reserved`.
