# Telecom Mod

Simulation de reseaux de telecommunications dans Minecraft : cables cuivre/fibre, routeurs, serveurs, antennes multibandes, smartphone, tests de debit et supervision web.

La [roadmap](ROADMAP_REALISME_ET_OPTIMISATION.md) se concentre sur la construction et l'utilisation du reseau, sans gestion electrique, tickets ou economie. Elle couvre les cartes de couverture, les antennes configurables, le partage radio, les IP fixes stables et les speedtests. Les faisceaux hertziens raccordent les sites sans cable entre eux ; les ports independants et la chaine FTTH detaillee constituent les prochains lots de construction.

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

### Diagnostics des recalculs

Premiere etape du lot d'optimisation des grands reseaux : mesurer le comportement existant avant de remplacer le retracage global. Les diagnostics sont desactives par defaut, reserves aux administrateurs et propres a la dimension de la commande :

```text
/telecom diagnostics start
/telecom diagnostics status
/telecom diagnostics stop
```

`start` ouvre une nouvelle mesure et efface les precedentes ; `status` (ou `/telecom diagnostics`) affiche le bilan dans le chat et les logs ; `stop` gele les resultats sans les effacer. Rien n'est sauvegarde dans le monde. Les demandes de recalcul et les timers existants ne sont ni effaces ni modifies par ces commandes. Une demande anterieure a `start` est signalee `UNSPECIFIED` lors de son traitement, sans reconstituer son attente passee.

- Causes et nombre de demandes : pose/retrait de cable, decouverte/retrait d'equipement, connexion de joueur et recalcul explicite. Les causes d'un meme lot sont conservees ; le timer de connexion reste separe des demandes immediates.
- Fenetres bornees : 1 200 ticks serveur et 128 recalculs, avec p50/p95/p99 et maximum en millisecondes. Les percentiles utilisent le rang superieur, sans interpolation ; les compteurs de demandes et de succes/echecs couvrent toute la mesure.
- Ticks mesures entre les evenements serveur `Pre` et `Post`, autres traitements du serveur compris. Cela exclut l'attente entre ticks et ne mesure pas seulement Telecom. Les callbacks d'autres mods au meme niveau de priorite peuvent etre hors de cette fenetre.
- Dernier recalcul : noeuds, liens avant/apres, duree, attente depuis la premiere demande du lot, puis delai jusqu'a publication du graphe. `publication=-1` signifie un echec, pas une liaison disponible. La publication n'est pas un acquittement client ni une garantie que la liaison demandee existe.
- `traceSteps` compte les etapes du parcours ; `blockReads` les lectures explicites de blocs, repetitions incluses ; `chunkRequests` les appels explicites a `getChunk(FULL, true)`, pas les chunks distincts ni les generations effectives. Le chargement force du traceur historique reste possible et n'est pas corrige par ce lot.
- `allocatedBytes` mesure les octets alloues sur le thread courant pendant le recalcul, si la JVM expose un compteur actif (`-1` sinon). Ce n'est ni la memoire retenue ni les allocations des workers de chunks. `pathCopies` et `copiedPathReferences` comptent les copies logiques de chemins du traceur, y compris la copie defensive de chaque lien, pas les redimensionnements internes aux collections.
- `skippedTrafficTicks` compte les passages ou le recalcul differe fait sauter l'allocation, meme en l'absence de trafic actif. Le comportement et les limites du reseau restent inchanges.

Une reference reproductible s'execute dans le monde de test isole :

```sh
./gradlew runGameTestServer -PnetworkBaseline=true
```

Le scenario cree 20 branches independantes avec **200 routeurs, 20 antennes, 20 serveurs et 860 blocs de fibre**, effectue 8 recalculs de chauffe, puis 40 coupures/reparations d'une seule branche et un recalcul programme comme lors d'une connexion joueur. Il verifie les connexions et les IP, teste les commandes de diagnostics et ecrit les bilans `NETWORK_BASELINE` ainsi que les echantillons `NETWORK_BASELINE_SAMPLE` dans `build/gametest/logs/latest.log`. Sans cette propriete, le benchmark n'est pas ajoute a la suite GameTest.

Reference locale du 18 septembre 2026, Java 21.0.11, macOS ARM64 :

| Mesure | Echantillons | p50 | p95 | p99 | Maximum |
| --- | --- | --- | --- | --- | --- |
| Tick serveur Pre/Post | 222 | 1,386 ms | 12,679 ms | 14,511 ms | 60,552 ms |
| Recalcul filaire | 41 | 3,959 ms | 14,160 ms | 59,667 ms | 59,667 ms |

Une coupure locale parcourt encore 11 226 etapes, lit 57 090 blocs et demande 57 330 fois un chunk ; elle copie 163 589 references de chemins et alloue environ 6,3 a 6,4 Mo. Une reparation porte ces compteurs a 11 280 / 57 360 / 57 600 et 165 240 references. Les 41 recalculs font chacun sauter un passage d'allocation. Le dernier recalcul de connexion attend 101 ticks (166,720 ms ici), prend 3,186 ms et publie apres 169,906 ms. Un passage precedent avait donne un p95/p99 de recalcul de 16,519/16,865 ms : cette variabilite et le pic a 59,667 ms interdisent de tirer une garantie de fluidite de cette courte reference.

**Limites de cette reference :** topologie synthetique dans un vrai serveur GameTest, chunks prepares, antennes sans bandes actives, aucun joueur connecte, carte ouverte ou trafic simultane. Le cas connexion appelle le meme ordonnanceur ; il ne mesure pas une authentification reseau reelle. GameTest avance sans attendre 50 ms par tick : les 101 ticks ne correspondent donc pas ici aux quelque 5 secondes d'un serveur a 20 TPS. La chauffe JVM peut se poursuivre apres les 8 passages ; les temps dependent de la machine et de sa charge, contrairement aux compteurs de parcours de cette fixture. Ni les reseaux de 1 000/5 000 routeurs, ni les chunks froids/decharges, ni les longues dorsales ne sont valides par ces chiffres. La reference du monde utilisateur et les comparaisons avant/apres optimisation restent a realiser.

## Premier reseau

1. Ouvrir un monde creatif et l'onglet Telecom.
2. Placer un serveur et un routeur, puis les relier par un chemin continu de fibre standard.
3. Attendre le recalcul du graphe ou utiliser `/telecom recalculate` avec les droits administrateur.
4. Ouvrir le routeur a proximite pour lancer un test de debit.
5. Pour le mobile, relier une antenne au reseau, ouvrir sa configuration et activer au moins une bande.
6. Utiliser un smartphone pour consulter la reception et lancer un test mobile.

Le test doit disposer d'un chemin jusqu'a un serveur. Les debits et l'identite reseau utilises sont calcules cote serveur. Un meme joueur peut lancer plusieurs tests simultanes sur des appareils distincts : plusieurs routeurs et son mobile, par exemple. Un seul test actif est autorise par appareil, avec une limite globale de 256 sessions par dimension. Le choix de duree concerne chaque phase de debit ; les interfaces affichent aussi la duree totale nominale, par exemple 33 secondes pour 3 secondes de ping et deux phases de 15 secondes.

Les routeurs sont identifies par position, et non par IP ; le terminal mobile reste lie a l'UUID du joueur (plusieurs items smartphone du meme joueur representent donc le meme terminal logique). Chaque test dispose de son identifiant de session. Fermer un ecran n'annule pas les autres tests et leur suivi est restaure a la reouverture. Les liens communs partagent leur capacite. Le dashboard affiche l'etat et les resultats par routeur et ne bloque pas le demarrage sur un autre appareil.

Le protocole de synchronisation est passe en version **1.6**, avec les radios FH et les diagnostics de leurs liaisons sur les cartes, en plus des reglages radio des antennes, des capacites appliquees et du choix du serveur de speedtest. Le client et le serveur doivent utiliser le JAR actualise.

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

Les profils materiels par defaut sont 1000000 Mbps par categorie DOWN/UP pour serveur, NRO et collecte filaire d'antenne ; 100000 pour NRA/PM ; 10000 pour SR. Les routeurs utilisent les limites de leur modele, notamment 1000/700 pour Lite. **La collecte filaire d'une antenne ne represente pas sa capacite radio.** Les budgets radio par bande s'ajoutent a ces contraintes, sans remplacer les limites du reseau amont.

La sauvegarde ajoute `CapacityModelVersion=1`, sans changer `SchemaVersion=1` ni les baux IP. Les anciennes capacites non appliquees des equipements non-routeurs sont remplacees par les profils standard. Les routeurs anciens gardent un drapeau `CapacityNeedsSync` jusqu'au chargement normal de leur bloc, puis sont synchronises avec son modele sans changer leur IP ni reconstruire leur topologie. Les plafonds personnalises sauvegardes avec le nouveau modele restent conserves, y compris zero ; au chargement d'un routeur, ils ne peuvent pas depasser les capacites physiques de sa variante. Sauvegarder le monde avant migration.

Le modele physique est mis en cache par revision et n'est plus reconstruit pour chaque trajet ou tick inactif. Il est borne a 262144 references de positions, en plus des limites de noeuds/liens. L'ensemble des requetes d'allocation retenues est egalement borne a 262144 references de ressources apres deduplication par session. Un test excedentaire echoue avec `network_limit`, sans annuler les tests deja admis ; un refus HTTP de demarrage renvoie 503 avec ce code et n'est pas rejoue automatiquement. Ces limites ne remplacent pas les benchmarks sur de grands reseaux.

L'ecran d'un serveur presente desormais les usages et capacites de cet equipement, et non les totaux de toute la dimension. Son actualisation est liee a la vue, au serveur, a la dimension et a la connexion : une ancienne reponse ne peut pas rouvrir l'ecran ferme ni modifier celui d'un autre serveur.

## Antennes et partage radio

Ouvrir une antenne en jeu pour choisir ses bandes et ses reglages radio, puis enregistrer :

- **Secteurs :** omnidirectionnel, ou un a trois secteurs regulierement espaces. Tous utilisent les bandes de l'antenne et partagent sa capacite par bande ; ajouter des secteurs ne multiplie pas le debit.
- **Orientation :** de 0 a 359 degres ; 0 pointe au sud (+Z), 90 a l'ouest (-X), 180 au nord et 270 a l'est.
- **Inclinaison :** de -15 a 45 degres, positive vers le bas, pour les secteurs directionnels.
- **Puissance radio :** de 0 a 50 dBm. La reference de 30 dBm conserve l'ancien calcul ; il ne s'agit pas d'une alimentation electrique a construire.
- **Largeur de bande :** 25, 50 ou 100 % de la largeur de reference de chaque bande. Les references simplifiees sont 0,2 MHz en 2G, 5 MHz en 3G, 20 MHz en 4G, 100 MHz en 5G et 400 MHz pour la 5G a 26 GHz.

Les reglages sont sauvegardes dans le bloc et le graphe. Les anciennes antennes restent omnidirectionnelles, a 30 dBm et a pleine largeur. La puissance et le diagramme directionnel sont appliques avant les pertes du terrain, par le meme moteur sur le smartphone et les deux cartes. Modifier les reglages invalide la couverture ; les secteurs utilisent toujours un seul parcours d'obstacles par antenne et point de reception.

Les telephones partagent les ressources de chaque bande de leur antenne de service, ainsi que les cables et equipements en amont. Les bandes agregees s'additionnent, elles ne sont pas traitees comme des liens en serie. L'allocation utilise une repartition proportionnelle fixe entre les bandes accessibles d'un telephone et un budget de temps radio partage entre DOWN et UP. Ce modele ne realise pas une redistribution dynamique entre porteuses ni une allocation independante par secteur.

Le suivi par bande affiche la charge radio en Mbps equivalents descendants : l'upload est normalise selon le rapport des capacites nominales DOWN/UP, puis additionne au download. Les compteurs filaires et les resultats des speedtests restent des Mbps de donnees effectivement alloues. Les valeurs radio sont quantifiees au Mbps, avec un minimum nominal d'un Mbps par sens, notamment en 2G.

Les emetteurs dont les spectres se recouvrent degradent le debit estime selon leur puissance recue et un bruit de fond simplifie, y compris entre technologies. Cette approximation suppose les emetteurs actifs en permanence ; elle ne simule pas un ordonnanceur LTE/NR complet. Les cartes montrent la puissance recue, pas une garantie de debit ni une carte de SINR. Les trajets inconnus ne sont pas inventes ; les interferences d'emetteurs non observes ne peuvent pas etre estimees.

Le scan, le trafic passif et les tests mobiles utilisent le meme service radio. Ses resultats sont reutilises pendant au plus 20 ticks au meme point de reception, avec au plus 256 telephones memorises par dimension, 8192 noeuds examines, 128 antennes et 65536 sondages budgetes par scan. Un deplacement ou changement de configuration invalide le resultat memorise. Un depassement de budget est signale explicitement, sans publier un sous-ensemble d'antennes comme une estimation complete. La selection initiale reste deterministe ; une cellule concurrente de meme technologie doit gagner au moins 3 dB pendant 40 ticks d'observations continues. Une technologie plus recente doit atteindre -105 dBm et respecter ce delai. La perte de cellule ou un signal sous -110 dBm permet une reselection immediate.

Pendant un speedtest, un changement de cellule conserve l'identite du test, ses phases et le serveur choisi, puis recalcule le trajet depuis la nouvelle antenne. Un test manuel peut gagner du debit lorsque la reception s'ameliore ; le trafic passif garde sa demande initiale. Un changement de technologie ajuste la contribution radio au ping a 15/40/95/300 ms pour la 5G/4G/3G/2G, sans nouveau tirage aleatoire a chaque tick. Une perte radio ou l'absence de chemin vers ce serveur est un echec explicite, pas un basculement silencieux de destination. Les etats d'attachement sont effaces a la deconnexion et au changement de dimension. La distinction fonctionnelle 5G NSA/SA, les reseaux mobiles nommes et la configuration independante de chaque secteur restent a implementer.

### Point a corriger : reception mobile et chunks decharges

**Signalement :** le signal mobile disparait brusquement en s'eloignant d'une antenne, comme si sa reception dependait du chargement de son chunk. La cause exacte de ce cas n'a pas encore ete reproduite en jeu.

**Verification du code :** le scan utilise les antennes enregistrees dans le graphe, meme lorsque leur chunk est decharge ; leur bloc n'a pas besoin de rester charge ou affiche autour du joueur. En revanche, les donnees 3D necessaires a la source, au recepteur et le long du trajet doivent etre disponibles. Un cache froid, une eviction ou une restauration disque encore en attente peut rendre le trajet inconnu, sans prouver que le signal physique est absent. Une image PNG de la carte ne remplace pas ces donnees radio 3D.

Le scan serveur distingue actuellement `radio_lost`, `radio_unknown` et `radio_limit`. Un resultat inconnu peut toutefois rester memorise jusqu'a 20 ticks au meme point, meme si le terrain vient d'etre restaure, et peut interrompre un speedtest mobile. De plus, la barre d'etat de l'application speedtest ramene encore les scans non exploitables a un message generique "Aucun reseau", contrairement au HUD qui distingue les raisons. Les seuils physiques de reception, les bandes utilisees, les limites de calcul et une reponse ancienne peuvent aussi expliquer une transition brusque ; le dechargement de l'antenne n'est donc pas une cause etablie pour ce signalement.

References du diagnostic : [scan et attachement mobile](src/main/java/com/florentdubut/telecom/network/RadioAccessService.java), [propagation et terrain inconnu](src/main/java/com/florentdubut/telecom/network/SignalPropagator.java), [cache radio](src/main/java/com/florentdubut/telecom/network/RadioTerrainCache.java), [affichage du speedtest](src/main/java/com/florentdubut/telecom/client/gui/SmartphoneSpeedtestScreen.java).

**Comportement attendu et travaux a faire :**

- [ ] Garantir une reception equivalente avec des chunks charges ou decharges lorsque l'antenne, le recepteur, les reglages et les donnees fiables du terrain sont identiques. La distance d'affichage ou de simulation ne doit pas servir de limite artificielle de portee radio.
- [ ] Verifier le cycle reel de dechargement/rechargement : conserver le noeud, les bandes, l'orientation, la puissance et l'IP de l'antenne ; distinguer ce cycle de sa destruction, qui doit retirer l'emetteur sans laisser de signal fantome.
- [ ] Relancer rapidement les scans concernes lorsqu'une restauration du terrain radio aboutit, sans obliger le joueur a bouger. Preparer et reprendre les calculs avec des files et caches bornes, sans forcer le chargement des chunks ni supposer que le terrain manquant est de l'air.
- [ ] Distinguer sur tous les ecrans du smartphone et sur les cartes : reception confirmee, absence confirmee, terrain inconnu/calcul en attente, limite de calcul et ancienne mesure. Ne pas afficher un simple "Aucun reseau" pour un manque de donnees ; afficher l'age d'une eventuelle derniere mesure connue.
- [ ] Definir une politique bornee pour une indisponibilite temporaire du terrain pendant un speedtest : etat en attente ou suspension explicite, sans debit invente, sans conserver indefiniment un ancien signal et sans masquer une vraie sortie de couverture. Ce changement n'est pas encore implemente.
- [ ] Ajouter une reproduction en jeu et des regressions pour l'eloignement avec dechargement reel, la restauration disque differee, la pression sur le cache, le redemarrage et les limites de scan. Relever les positions, bandes et reglages, la presence du noeud, l'etat charge/decharge des chunks, la disponibilite du terrain et le motif exact renvoye par le serveur.

**Validation attendue :** pour un trajet connu et inchange, decharger le chunk de l'antenne ou des chunks intermediaires ne doit pas faire disparaitre une reception valide. Une restauration asynchrone doit permettre la reprise au meme point. Une zone jamais observee reste explicitement inconnue ; une antenne desactivee, detruite, hors portee ou masquee par une attenuation suffisante ne doit pas etre annoncee comme captee.

**Verification effectuee :** les suites `NetworkScanTest`, `SignalPropagatorTest` et `RadioTerrainCacheTest` ont ete reexecutees avec succes. Le cas de source dechargee avec terrain conserve est couvert, mais ces tests ne remplacent pas la reproduction complete du deplacement signale. Ce point documente le diagnostic et les corrections a realiser, pas une correction deja livree.

## Faisceaux hertziens

Une parabole FH (`telecom:microwave_dish`) transporte le trafic entre deux sites. Elle ne couvre pas les telephones : une antenne mobile distincte reste necessaire sur le site distant.

```text
Serveur -- cable -- Parabole A ~~~ FH ~~~ Parabole B -- cable -- Antenne mobile
```

1. Placer les deux paraboles et raccorder localement A au reseau, puis B au routeur ou a l'antenne distante. Utiliser de la fibre standard ; le cuivre est possible si l'autre equipement l'accepte deja (pas directement sur un serveur).
2. Ouvrir chaque parabole. Saisir les coordonnees de l'autre extremite, activer les deux radios et choisir la meme frequence et le meme canal.
3. Orienter chaque parabole vers l'autre, en azimut et en elevation. Le bouton de visee calcule les angles vers les coordonnees saisies ; il ne configure pas l'autre site.
4. Enregistrer les deux configurations. L'association doit etre reciproque, dans la meme dimension ; un simple ciblage unilateral ne cree pas de liaison.
5. Lire le diagnostic serveur dans l'ecran et sur les cartes : etat, capacite, latence et obstacle eventuel. Une liaison en attente ou inconnue n'est pas declaree operationnelle.

Les radios proposent les profils 6, 11, 18 et 38 GHz, avec 16 canaux par profil. Le canal et la frequence doivent correspondre aux deux extremites. L'azimut suit Minecraft (0 sud, 90 ouest, 180 nord, 270 est) ; l'elevation FH est positive vers le haut, contrairement a l'inclinaison vers le bas des secteurs mobiles. Le modele de bloc represente une orientation horizontale simplifiee ; le calcul utilise les angles configures.

| Profil | Capacite nominale partagee |
| --- | --- |
| 6 GHz | 300 Mbps |
| 11 GHz | 600 Mbps |
| 18 GHz | 1000 Mbps |
| 38 GHz | 2000 Mbps |

La portee maximale est de 4096 blocs. L'alignement est considere plein jusqu'a 15 degres d'erreur, degrade au-dela et coupe a 30 degres. Le budget radio simplifie utilise une puissance fixe de 20 dBm, un gain de 25 dBi par extremite et un seuil de reception de -70 dBm ; le debit nominal requiert une marge de 20 dB. Chaque saut ajoute 1 ms de traitement plus son temps de propagation arrondi a la milliseconde superieure, soit 2 ms dans cette plage de distances. Ces profils sont des regles de simulation, pas un dimensionnement radio certifie.

La qualite depend de la distance, de la frequence, de l'alignement des deux extremites et des obstacles. Le controle inclut un parcours conservateur des voxels de la zone de degagement a 60 % du premier ellipsoide de Fresnel, pas seulement son axe ou quelques points sur des anneaux. Il peut inclure des voxels supplementaires en bordure ; tout materiau non vide dans le volume verifie bloque le lien. Le calcul reprend progressivement sur le thread serveur et reutilise les donnees 3D du terrain radio observe. Il ne charge ni ne genere des chunks pour chercher un site distant ; un trajet jamais observe ou incomplet reste inconnu.

Une liaison operationnelle devient un lien du graphe avec un budget **DOWN + UP partage**. Tous les utilisateurs du site distant consomment cette capacite et restent limites par les cables, equipements et liaisons en amont. Le budget local de chaque parabole est de 10000 Mbps par sens ; il ne remplace pas le plafond radio partage du faisceau. Les positions dans l'air ne deviennent pas des cables physiques. Une coupure, un desalignement ou un changement de configuration retire la liaison du routage ; les cartes conservent son diagnostic. Un chemin de secours vers le meme serveur peut etre utilise s'il existe.

Pour un relais A -> B -> C, installer **deux paraboles sur B** et les relier localement : une associee a A, l'autre a C. Chaque saut ajoute sa capacite limite et sa latence. Couper le cable entre les deux paraboles de B ou la collecte de A coupe le service en aval, meme si les antennes mobiles continuent d'emettre.

Les configurations et les IP sont sauvegardees. Les liens operationnels sont derives et doivent etre verifies a nouveau apres redemarrage ; une ancienne sauvegarde ne prouve pas que le trajet est toujours libre. Un recalcul du cablage conserve les FH valides et ne les transforme pas en cables. Sauvegarder le monde avant migration ; une ancienne version du mod ne connait pas le nouveau type de noeud.

Le service limite la decouverte a 8192 noeuds, 256 paraboles et 128 liaisons/diagnostics par dimension. Les travaux de propagation progressent avec un budget de 2 ms par tick et au plus 1048576 operations candidates par trajet, y compris les controles geometriques ; une limite atteinte est signalee plutot que de publier un trajet partiellement verifie. La meteo, les interferences entre plusieurs FH et l'allocation dynamique de canaux ne sont pas simulees. Les cartes distinguent les faisceaux des cables, y compris lorsqu'ils relient les memes extremites.

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

Une antenne dont le trajet est inconnu n'empeche pas la reception d'une autre antenne dont le signal est calcule. La selection initiale du telephone et la carte utilisent le meme classement des trajets connus ; ils n'annoncent un trajet inconnu que si aucun signal exploitable n'est confirme. En deplacement, le telephone conserve temporairement sa cellule selon l'hysteresis decrite plus haut, alors que la carte reste une prediction sans historique d'attachement. Selectionner la meme antenne pour comparer leurs puissances recues.

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

Pour la recette interactive du lot radio :

1. Regler un secteur vers le telephone, noter son signal, puis tourner l'antenne de 180 degres. Comparer la puissance sur le smartphone et les cartes avec cette antenne selectionnee, puis sauvegarder et redemarrer pour verifier les reglages.
2. Lancer deux tests mobiles sur la meme bande, puis sur deux antennes independantes. Verifier le partage radio et la limitation par un cable de collecte plus lent ; reduire la largeur de bande pendant un test.
3. Se deplacer entre deux cellules, verifier la stabilite pres de la frontiere et le maintien du serveur de speedtest choisi. Couper ensuite la collecte ou desactiver toutes les bandes pour verifier les erreurs explicites.
4. Fermer l'ecran d'antenne pendant une actualisation, ouvrir une autre antenne et changer de dimension. Une ancienne actualisation ne doit ni rouvrir ni remplacer la nouvelle vue.

Pour la recette interactive des faisceaux hertziens :

1. Construire une paire sur des supports solides, configurer les deux extremites et lancer un speedtest depuis le site distant. Comparer le diagnostic de chaque parabole et celui des deux cartes.
2. Desaligner une parabole, modifier son canal, puis retablir les reglages. Placer un obstacle sur le trajet, puis dans son degagement de Fresnel sans couper l'axe ; verifier les erreurs et la reprise apres retrait.
3. Faire fonctionner un relais avec deux paraboles interconnectees au site intermediaire. Couper successivement la collecte, le cable du relais et un faisceau ; verifier que le site distant ne dispose plus de service sans chemin de secours.
4. Tester une liaison longue sur un terrain deja observe, s'eloigner pour decharger ses chunks, puis redemarrer le serveur. Verifier que les reglages et les IP persistent et que les trajets se revalident sans generation forcee de chunks.
5. Tester plusieurs utilisateurs, les cables paralleles aux FH et la fermeture/reouverture rapide des ecrans. Verifier que les debits partagent les budgets et que les infobulles ne conservent pas un ancien etat operationnel.

## References et licence

- [Documentation NeoForge](https://docs.neoforged.net/docs/1.21.11/)
- [Conditions des mappings Mojang](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)
- Licence declaree du mod : `All Rights Reserved`.
