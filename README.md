# Telecom Mod

Simulation de reseaux de telecommunications dans Minecraft : cables cuivre/fibre, routeurs, serveurs, antennes multibandes, smartphone, tests de debit et supervision web.

La [roadmap](ROADMAP_REALISME_ET_OPTIMISATION.md) se concentre sur la construction et l'utilisation du reseau, sans gestion electrique, tickets ou economie. La couverture calculee sur les cartes web et en jeu, les IP fixes stables et le choix du serveur de speedtest sont implementes. Les faisceaux hertziens entre sites et la chaine FTTH detaillee restent a implementer.

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

Le test doit disposer d'un chemin jusqu'a un serveur. Les debits et l'identite reseau utilises sont calcules cote serveur. Un meme joueur peut lancer plusieurs tests simultanes sur des appareils distincts : plusieurs routeurs et son mobile, par exemple. Un seul test actif est autorise par appareil, avec une limite globale de 256 sessions par dimension. Le choix de duree concerne chaque phase de debit ; les interfaces affichent aussi la duree totale nominale, par exemple 33 secondes pour 3 secondes de ping et deux phases de 15 secondes.

Les routeurs sont identifies par position, et non par IP ; le terminal mobile reste lie a l'UUID du joueur (plusieurs items smartphone du meme joueur representent donc le meme terminal logique). Chaque test dispose de son identifiant de session. Fermer un ecran n'annule pas les autres tests et leur suivi est restaure a la reouverture. Les liens communs partagent leur capacite. Le dashboard affiche l'etat et les resultats par routeur et ne bloque pas le demarrage sur un autre appareil.

Le protocole de synchronisation est passe en version **1.4**, avec les capacites appliquees dans les outils et le suivi correle des ecrans serveur, en plus de la couverture et du choix du serveur de speedtest. Le client et le serveur doivent utiliser le JAR actualise.

### Speedtests animes

Le telephone, le routeur et le dashboard affichent un grand compteur anime, les courbes DOWN cyan et UP orange, le ping, les moyennes et la progression totale. Les panneaux Minecraft agrandissent leur courbe lorsque la fenetre le permet. La courbe est une fenetre glissante des 120 derniers points recus par phase, avec son echelle de maximum observe et sa duree indiquees ; ce n'est pas un historique permanent de tous les tests.

Les fluctuations viennent de la simulation serveur, pas de nombres aleatoires ajoutes a l'affichage. Chaque phase manuelle monte en charge pendant environ une seconde depuis 35 % du plafond, puis sa demande varie progressivement entre 94 et 100 % du plafond effectif. Ce profil est applique **avant** le partage de la bande passante : la concurrence et les goulots restent prioritaires, les budgets ne sont jamais depasses et une forte congestion peut produire un plateau. Les faibles debits restent quantifies au Mbps. Le trafic passif ne recoit pas ce profil.

Les resultats DOWN/UP des nouveaux tests sont les moyennes arrondies des allocations reellement obtenues dans chaque phase, montee en charge et vrais echantillons a zero inclus. Ils ne dependent plus du dernier tick. Un trafic passif ne remplace pas le resultat manuel sauvegarde du routeur. Les anciens resultats sauvegardes sont conserves et affiches comme tels, sans leur inventer une courbe ni les requalifier en moyennes.

Le lissage de presentation ne depasse pas la derniere mesure recue ; une baisse est prise en compte sans maintenir un faux debit superieur. Les compteurs d'usage courant et la derniere mesure valide sont distincts : un recalcul suspendu ne cree pas un point nul artificiel. Les interfaces gelent leur animation en cas de donnees anciennes, n'inventent pas de fin de phase et attendent le serveur pour annoncer la fin. En cas d'echec, elles gardent le dernier avancement confirme ; sans observation prealable, l'avancement est indique comme inconnu.

Le dashboard conserve sa cadence de lecture habituelle, environ deux secondes hors attente serveur, tandis que Minecraft recoit nominalement un echantillon tous les deux ticks. Leurs courbes peuvent donc etre differentes sans que les resultats moyens serveur divergent. La preference navigateur de reduction des animations est respectee. Les caches de presentation restent bornes a 256 appareils et isoles par connexion et par monde ou dimension ; l'annulation et l'historique durable des tests restent a implementer.

### Adresses stables

Les IP fixes sont conservees dans les champs `IP`/`CIDR` existants du graphe, par dimension. Un recalcul, une coupure, une fusion de reseaux ou le retrait d'un serveur ne renumerote pas les autres equipements. Une adresse n'est rendue disponible que lorsque son noeud est supprime. Posseder une IP ne signifie pas avoir un chemin vers un serveur.

Les adresses canoniques existantes du pool prive `10.0.0.0/8` sont preservees. Au chargement, les adresses absentes, invalides, hors pool et les doublons sont remplaces de facon deterministe ; les anciennes adresses de serveur `0.0.0.0` deviennent donc des adresses uniques. En cas de doublon, le noeud ayant la plus petite position encodee conserve l'adresse. Les allocations nouvelles ou reparees utilisent un CIDR d'hote `/32` ; les anciens CIDR non vides d'une adresse conservee restent presents a titre informatif, sans imposer une nouvelle hierarchie de routage.

Les IP mobiles restent allouees par UUID et persistees par dimension dans la plage distincte `172.16.0.0/12`, independamment de la position de l'antenne. Le schema NBT du graphe reste en version 1. Sauvegarder le monde avant la premiere migration.

### Choix du serveur de speedtest

Le routeur et l'application speedtest du smartphone proposent un bouton **Serveur**. Dans le dashboard, le selecteur se trouve dans le panneau du routeur. Choisir **Auto** ou un serveur explicitement : chaque entree affiche son nom genere a partir des coordonnees, son identifiant, une latence estimee et sa disponibilite depuis l'appareil. Le catalogue web affiche aussi la capacite du chemin filaire. L'estimation n'est pas une mesure de latence en charge.

Le serveur Minecraft revalide la destination au demarrage. Un serveur choisi qui a disparu ou est inaccessible provoque un refus explicite, sans repli automatique. Si le serveur ou le chemin disparait pendant le test, celui-ci echoue avec sa destination et sa raison d'echec. Le mode Auto reste disponible et choisit le serveur accessible au ping de chemin le plus faible. Les resultats et le suivi restent independants par appareil.

La decouverte est limitee a 128 serveurs affiches, avec indication de troncature. Elle effectue un seul parcours du graphe ; au-dela de 8192 noeuds, 16384 liens ou 262144 references de positions physiques, le catalogue est refuse avec une erreur explicite plutot que lancer une recherche sans borne. Les requetes Minecraft sont limitees en frequence et correlees a l'appareil, a la dimension et a l'ecran courant. Aucun chunk n'est force pour constituer le catalogue.

L'API expose `GET /api/speedtest/servers?pos=<position_du_routeur>`. `POST /api/speedtest` accepte un champ `serverId` optionnel, encode en chaine decimale ; absent ou vide, il conserve le mode Auto. Les instantanes et acquittements exposent la destination utilisee ; les erreurs portent un `errorCode` exploitable par l'interface.

### Capacites et partage des debits

Toutes les capacites et mesures du moteur sont en **Mbps**, avec conversion decimale en Gbps dans les interfaces. Le catalogue expose le plafond descendant du trajet, pas un debit garanti. Les budgets des equipements sont appliques separement aux trafics DOWN et UP ; les liens et positions de cable communes partagent un seul budget **DOWN + UP**. Ce modele ne suppose pas un lien physique full-duplex.

| Cable | Capacite nominale par defaut | Capacite effective |
| --- | --- | --- |
| Cuivre | 1000 Mbps | `min(nominale, max(10, 1000 - 2 * longueur))` |
| Fibre standard | 10000 Mbps | Capacite nominale du lien |
| Fibre moyenne | 100000 Mbps | Capacite nominale du lien |
| Grosse fibre | 1000000 Mbps | Capacite nominale du lien |

L'attenuation cuivre est appliquee **par segment entre equipements**, et non sur la somme de tous les segments du trajet. Une capacite nominale personnalisee plus faible, y compris zero, reste un plafond. Les valeurs admises vont de 0 a 1000000 Mbps. Un retracage conserve la capacite configuree d'un lien dont les extremites et le type sont inchanges. Cette approximation ne remplace pas la future modelisation ADSL/VDSL.

Un bloc physique commun a plusieurs liens n'est compte qu'une fois par session ; son budget est le minimum des capacites effectives des liens qui le contiennent. Les extremites des liens sont traitees comme des equipements, pas comme des cables partages. Deux cables arrivant au meme serveur ne partagent donc plus artificiellement la capacite du plus petit cable : ils restent toutefois soumis au budget du serveur.

L'allocateur redistribue les capacites restantes entre les demandes. Il conserve des credits d'arrondi bornes pour repartir dans le temps les petits debits : deux utilisateurs sur 1 Mbps peuvent alterner, sans que l'arrondi condamne toujours le meme a zero. La variation aleatoire qui retirait systematiquement une partie du debit alloue a ete supprimee. Les compteurs des cables, equipements, outils et du dashboard utilisent ces allocations reelles ; le dashboard affiche par exemple 100 % pour 50 Mbps DOWN + 50 Mbps UP sur un lien partage de 100 Mbps.

Les profils materiels par defaut sont 1000000 Mbps par categorie DOWN/UP pour serveur, NRO et collecte filaire d'antenne ; 100000 pour NRA/PM ; 10000 pour SR. Les routeurs utilisent les limites de leur modele, notamment 1000/700 pour Lite. **La collecte filaire d'une antenne ne represente pas sa capacite radio.** Le partage radio par bande et les interferences restent un lot distinct.

La sauvegarde ajoute `CapacityModelVersion=1`, sans changer `SchemaVersion=1` ni les baux IP. Les anciennes capacites non appliquees des equipements non-routeurs sont remplacees par les profils standard. Les routeurs anciens gardent un drapeau `CapacityNeedsSync` jusqu'au chargement normal de leur bloc, puis sont synchronises avec son modele sans changer leur IP ni reconstruire leur topologie. Les plafonds personnalises sauvegardes avec le nouveau modele restent conserves, y compris zero ; au chargement d'un routeur, ils ne peuvent pas depasser les capacites physiques de sa variante. Sauvegarder le monde avant migration.

Le modele physique est mis en cache par revision et n'est plus reconstruit pour chaque trajet ou tick inactif. Il est borne a 262144 references de positions, en plus des limites de noeuds/liens. L'ensemble des requetes d'allocation retenues est egalement borne a 262144 references de ressources apres deduplication par session. Un test excedentaire echoue avec `network_limit`, sans annuler les tests deja admis ; un refus HTTP de demarrage renvoie 503 avec ce code et n'est pas rejoue automatiquement. Ces limites ne remplacent pas les benchmarks sur de grands reseaux.

L'ecran d'un serveur presente desormais les usages et capacites de cet equipement, et non les totaux de toute la dimension. Son actualisation est liee a la vue, au serveur, a la dimension et a la connexion : une ancienne reponse ne peut pas rouvrir l'ecran ferme ni modifier celui d'un autre serveur.

## Dashboard et securite

Par defaut, le dashboard ecoute seulement sur `http://127.0.0.1:8080`. Il ne comporte aucune authentification : les actions sont directement accessibles aux clients autorises par les controles d'hote et d'origine.

**Toute personne pouvant acceder au dashboard peut lancer les speedtests, generer du terrain et annuler les travaux.** Conserver l'ecoute locale ou restreindre l'acces reseau avec un pare-feu, un tunnel ou un reverse proxy. Les controles d'origine ne remplacent pas une authentification.

### Fond de carte permanent

Chaque chunk charge est capture progressivement une seule fois, puis enregistre en PNG dans le dossier du monde :

```text
<monde>/telecom-map/minecraft/overworld/<region_x>_<region_z>/<chunk_x>_<chunk_z>.png
```

Ces images de base n'expirent pas et ne sont pas recalculees lorsque le terrain change. Elles restent disponibles apres dechargement des chunks et redemarrage. Les changements de monde utilisent un identifiant de carte distinct.

Pour l'affichage, elles sont assemblees progressivement dans **une seule image globale**, conservee avec ses coordonnees sous `_overview/world.map`. Le navigateur telecharge cette image une fois par revision via `/api/map-image` ; deplacer ou zoomer la carte ne provoque aucun nouveau telechargement du fond. Les anciens appels d'atlas `/api/terrain` ne sont plus utilises.

Les nouvelles captures sont regroupees en batches sur un worker. Lorsque la carte s'etend sans changer d'echelle, les anciens pixels sont recopies et seules les nouvelles zones sont ajoutees. Les PNG de base restent immuables. Le dernier instantane valide reste affiche pendant la preparation du suivant ; les coordonnees HTTP accompagnent exactement les pixels recus.

L'image est plafonnee a **2048 x 2048 pixels**, soit 16 Mio decodes. Son echelle augmente si le monde cartographie devient tres grand : une image unique ne peut pas conserver indefiniment un pixel par bloc. Les sources detaillees restent sur disque. Les requetes de topologie et du calque radio sont independantes de cet unique telechargement du fond.

Seule la premiere capture exige un chunk deja charge. La capture est amorcee avec les chunks disponibles puis suit ceux envoyes aux joueurs, avec une file limitee et une seule lecture terrain en attente. Un terrain pas encore disponible n'est pas sauvegarde sous forme de tuile vide. Une image de base illisible n'est pas remplacee silencieusement. Le stockage disque augmente avec les zones capturees ; il n'est pas purge automatiquement.

Le fond represente donc volontairement le terrain au moment de sa premiere capture. **La couverture radio reste dynamique** et utilise toujours les blocs du monde, pas les couleurs du PNG.

### Carte de couverture calculee

1. Activer des bandes sur une antenne dans le monde.
2. Ouvrir le dashboard et cocher **Couverture calculee** dans les controles d'affichage.
3. Choisir toutes les antennes ou un site, puis eventuellement une technologie et une bande.
4. Choisir la surface ou une hauteur Y fixe pour examiner un etage ou un tunnel.
5. Attendre le calcul progressif ; survoler une case pour lire signal, antenne, hauteur et acces au reseau.

La couverture est calculee independamment pour la **2G, 3G, 4G et 5G**. Le filtre affiche les resultats propres a chaque technologie ; "Toutes (dominante)" est uniquement une vue de synthese. Dans le dashboard, changer de technologie reutilise les donnees deja recues, sans relancer les rayons ni retelecharger les tuiles pretes.

Les precisions proposees sont **1 bloc**, **8 blocs (demi-chunk)** et **16 blocs (chunk)**, plus un mode Auto. Le pas de 1 calcule chaque bloc de la grille, avec au plus 256 points par tuile. Les choix restent des precisions souhaitees : au grand dezoom, un apercu plus grossier preserve le viewport complet et les performances. Le pas reel est toujours affiche ; zoomer jusqu'a "precision demandee atteinte" pour obtenir exactement le pas choisi. Le zoom maximal permet le pas 1 sur un viewport 4K.

Le terrain traversant chaque rayon est lu bloc par bloc par le meme moteur que le smartphone. Les bandes d'une antenne partagent un seul parcours des obstacles, avec pertes et resultats independants. Les murs, le relief, l'eau et les categories de materiaux influencent le signal. Les demi-blocs et formes complexes restent approximatifs ; ce n'est pas une simulation electromagnetique exacte.

La surface correspond a deux blocs au-dessus de la hauteur bloquante hors feuilles. Le mode Y fixe est utile a l'interieur des batiments. Le moteur commun est limite a **4 096 blocs** de distance ; au-dela, le signal est considere hors portee dans cette simulation.

Les chunks manquants sont marques **inconnus**, jamais assimiles a de l'air. La carte ne charge pas de terrain supplementaire. Une reception radio peut exister sans chemin vers un serveur ; le survol distingue le signal et la disponibilite de ce chemin. Les releves Nperf restent un calque separe et historique.

Une antenne dont le trajet est inconnu n'empeche pas la reception d'une autre antenne dont le signal est calcule. Le telephone et la carte choisissent le meilleur signal parmi les trajets connus ; ils n'annoncent un trajet inconnu que si aucun signal exploitable n'est confirme.

Le terrain radio observe est maintenant conserve en **3D**, independamment du PNG de la carte : palettes copiees au chargement/dechargement, LRU de 256 chunks en memoire et au plus 4096 fichiers compresses par monde/dimension sous `<monde>/telecom-radio/<dimension>/`. Une antenne et des obstacles observes restent utilisables apres dechargement tant que leurs donnees sont conservees ; un chunk charge est toujours prioritaire sur sa copie. Une zone evincee a la fois de la memoire et du disque doit etre observee a nouveau. Les lectures/ecritures disque utilisent un worker et une file de 128 taches maximum, sans forcer de generation.

Le message "Trajet radio inconnu" signifie qu'une portion n'a jamais ete observee, que sa restauration disque est encore en cours ou que la persistance ne peut pas etre consideree fiable. Il ne faut pas remplacer ces donnees par de l'air. Apres un arret non propre ou une ecriture perdue, les snapshots concernes sont invalides par precaution. Une simple image de surface ne suffit pas a reconstituer les obstacles.

Les changements de blocs, y compris `/setblock strict`, invalident les calculs dependants du trajet, meme hors de la tuile affichee. Les changements d'antennes et de topologie sont egalement suivis. Le navigateur verifie la revision du modele toutes les deux secondes lorsque le calque est actif ; le rafraichissement peut etre retarde par la charge.

Le calcul reprend par petites tranches sur le thread serveur, avec un budget cooperatif partage de 2 ms par tick. Les files sont limitees a 16 travaux par dimension et les caches a 128 tuiles physiques partagees entre technologies. Les resultats ne sont plus recalcules arbitrairement toutes les 30 secondes : ils restent reutilisables jusqu'a une vraie invalidation, une eviction ou cinq minutes d'inactivite. Le navigateur revalide ses donnees apres 30 secondes sans imposer un nouveau calcul physique. Le calque radio statique utilise un canvas intermediaire borne a 32 Mio pour eviter de redessiner chaque cellule a chaque frame.

Le test `CoveragePerformanceTest` compare une grille de 16 x 16 blocs sur 14 bandes : 508 303 sondages terrain separes contre 38 600 partages, soit environ 13,17 fois moins de lectures dans ce scenario. Ce n'est pas une garantie du meme gain en temps total sur une ville : dimensions, obstacles et nombre d'antennes comptent encore.

La carte web concerne l'Overworld. La carte reseau dans Minecraft propose aussi la couverture calculee, dans la dimension du joueur, sans connexion au dashboard. Elle reutilise les memes calculs et le meme cache serveur. Les reponses radio sont bornees a 256 cellules par tuile ; les requetes sont limitees cote serveur et les reponses d'une ancienne vue ou dimension sont ignorees. Le fond PNG du dashboard n'est pas affiche dans cette carte en jeu.

Dans Minecraft, ouvrir l'item carte reseau et activer le calque de couverture. Les controles permettent de choisir technologie, bande, hauteur de reception et precision ; cliquer sur une antenne permet de l'isoler. Deplacer la carte par glissement et zoomer avec la molette. La legende distingue signal fort, moyen, faible, absent et donnees inconnues ; le survol precise la hauteur, la source radio et la disponibilite d'un chemin vers un serveur. Les filtres de la carte en jeu redemandent la selection au serveur, mais reutilisent les calculs physiques deja en cache.

Le mode **Y mobile** utilise le meme point de reception que le smartphone (Y du bloc du joueur + 1). La carte conserve au plus 16 tuiles visibles, garde celles encore dans la vue lors d'un deplacement et masque les donnees non revalidees apres 100 ticks client. Un calcul depassant une limite s'arrete avec une indication au survol : choisir une antenne ou une precision plus grossiere, puis reactiver le calque si necessaire. Le fond et les controles de la carte en jeu restent a verifier visuellement en multijoueur.

### Travaux sur une zone

Dans le dashboard, activer **Selectionner une zone**. Dessiner un rectangle sur la carte ou saisir ses bornes.

- **Generer le terrain** : cocher la confirmation, puis lancer. Les chunks sont charges/generes progressivement, un a la fois, et leurs captures completent l'image globale. Les PNG deja enregistres restent inchanges.
- **Calculer couverture** : choisir un pas exact de 1, 8 ou 16 blocs et les filtres radio, puis lancer. Le calcul de cette zone est relance et reste au pas choisi, meme au dezoom. **Retour vue automatique** restaure le fonctionnement normal de la carte.
- La progression et l'annulation sont disponibles. Une annulation arrete le traitement suivant, mais ne retire pas les chunks deja crees ni la generation vanilla deja engagee.

Limites : un travail actif, **4096 chunks** maximum pour le terrain et **1024 tuiles physiques de couverture** (262 144 points maximum). Pour des zones carrees alignees, cela represente 1024 x 1024 blocs de terrain, ou une couverture de 512 x 512 blocs au pas 1, 4096 x 4096 au pas 8 et 8192 x 8192 au pas 16. Les limites sont arrondies aux chunks ou aux tuiles de calcul.

Le serveur traite toute la zone progressivement, sans augmenter le nombre de chunks generes simultanement. Le navigateur travaille sur une fenetre de 64 tuiles de detail au maximum et conserve tous les descripteurs de zone pour la navigation. A grand dezoom, zoomer pour voir les details exacts ou revenir a la vue automatique pour un apercu global. Les grandes zones peuvent prendre longtemps ; Minecraft peut generer des voisins necessaires. Faire une sauvegarde du monde avant une preparation importante.

Le calcul radio seul ne genere pas de terrain : preparer d'abord la zone si necessaire. Les trajets vers une antenne passant hors de la zone peuvent encore manquer de donnees et rester inconnus. Les travaux ne sont pas repris automatiquement apres redemarrage ; les captures deja sauvegardees restent disponibles.

### Configuration HTTP

| Configuration | Defaut | Role |
| --- | --- | --- |
| Propriete JVM `telecom.http.enabled` | `true` | Activer HTTP. |
| Propriete JVM `telecom.http.bind` | `127.0.0.1` | Adresse d'ecoute. |
| Propriete JVM `telecom.http.port` | `8080` | Port de 1 a 65535. |
| Propriete JVM `telecom.http.origins` | vide | Origines supplementaires exactes, separees par virgules, sans chemin ni wildcard. |

Les proprietes `-Dtelecom.http.*` doivent etre passees a la JVM du jeu ou du serveur, pas seulement a la JVM Gradle. Aucune cle ni session de connexion n'est requise pour le dashboard. Les limites de taille, la confirmation de generation et les controles d'identite du monde restent obligatoires.

Pour le developpement web, `npm --prefix web-dashboard run dev` lance Vite sur loopback et proxifie `/api` vers le port 8080. Autoriser son origine exacte, par exemple `http://localhost:5173`, dans la JVM Minecraft. Une origine autorisee permet aussi son autorite HTTP pour les controles `Host`, notamment derriere un reverse proxy.

Pour un acces distant, utiliser HTTPS via reverse proxy ou un tunnel et limiter les clients autorises. Le mod n'integre ni TLS ni authentification ; ne pas l'exposer directement sur Internet.

### Limites operationnelles

- Requetes de mutation limitees a 4 Kio ; quatre workers HTTP et une file de 32 requetes.
- Lectures non bloquantes : `202` signifie que le resultat attend un tick Minecraft. Une seule lecture du monde est en attente ou en execution ; ses resultats sont conserves au plus 30 secondes, avec 64 resultats maximum.
- Une tuile sans image sauvegardee dont le chunk n'est pas charge renvoie `204`, sans PNG vide ni erreur `404`. Cette absence est memorisee 30 secondes avant une nouvelle verification.
- Les `429`/`503` restent de vraies limitations ; le dashboard respecte `Retry-After` et ralentit ses requetes. Un timeout d'une mutation deja commencee peut laisser son resultat indetermine ; l'attente de 750 ms concerne uniquement ces actions.
- Premier rendu des tuiles uniquement pour les chunks deja charges ; aucune generation de terrain par consultation de la carte.
- PNG de base permanents sur disque ; une seule image globale diffusee au navigateur, bornee a 2048 x 2048 pixels, avec revision et ETag.
- Certains plafonds de noeuds restent indicatifs : l'unification de toutes les capacites et la congestion realiste appartiennent aux lots suivants.

**Jeu solo en pause :** les nouvelles lectures et les calculs ne peuvent pas progresser sans ticks. Les images deja sauvegardees restent accessibles et la page attend sans accumuler de taches. Reprendre le jeu permet de continuer. Si Minecraft se met en pause lorsque le navigateur prend le focus, `F3 + P` permet de desactiver cette pause automatique ; cela ne remplace pas la reprise d'une pause manuelle dans le menu.

## Tests

```sh
./gradlew test
./gradlew runGameTestServer
```

La suite JUnit couvre graphe/NBT, sessions, baux mobiles, codecs, ressources d'items, enregistrement du mod, block entities et HTTP. Le serveur ephemere JUnit ne cree pas de monde ; les interactions de blocs sont donc verifiees separement par GameTest.

Pour executer seulement les tests HTTP :

```sh
./gradlew test --rerun --tests '*TelecomHttpServerTest'
```

Tous les tests HTTP s'executent sans identifiants. Ils utilisent de vraies connexions locales et des objets Minecraft simules ; ils ne remplacent pas une recette interactive multijoueur. Les benchmarks Ville/Stress et la verification visuelle complete restent a realiser.

Pour la recette visuelle de la carte en jeu :

1. Activer une antenne, ouvrir la carte, choisir **Y mobile** et une grille de 1 bloc, puis zoomer jusqu'au pas effectif 1. Comparer la cellule aux coordonnees du joueur avec le smartphone, en tenant compte de son affichage arrondi en dBm.
2. Poser puis retirer un mur sur le trajet ; attendre la revalidation et verifier le changement de signal. Un terrain jamais observe doit rester inconnu, pas devenir un signal absent certain.
3. Choisir une technologie, une bande et une antenne ; se deplacer et zoomer. Les tuiles communes doivent rester affichees tant qu'elles sont valides ; les autres doivent indiquer un calcul en attente.
4. Ouvrir la carte avec deux joueurs, fermer/rouvrir rapidement puis changer de dimension. Verifier l'independance des vues et l'absence d'anciennes donnees radio.

## References et licence

- [Documentation NeoForge](https://docs.neoforged.net/docs/1.21.11/)
- [Conditions des mappings Mojang](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)
- Licence declaree du mod : `All Rights Reserved`.
