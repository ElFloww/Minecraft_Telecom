# Telecom Mod : construire et utiliser son reseau

Mise a jour : 15 septembre 2026.

## 1. Direction du projet

Construire un reseau de telecommunications inspire de la France, raccorder des equipements et l'utiliser dans Minecraft. Le realisme doit se voir dans les connexions, la couverture, les debits et les communications, sans transformer le mod en logiciel de gestion.

**Hors perimetre :** electricite, batteries, carburant, refroidissement, tickets, interventions administratives, facturation, contrats, gestion d'entreprise et pannes aleatoires imposees. Une rupture de cable ou une mauvaise configuration continue naturellement d'affecter le reseau.

Cette version remplace l'ancienne roadmap detaillee. Les cases ouvertes sont des fonctionnalites a faire, pas des fonctionnalites deja disponibles.

## 2. Base deja en place

- [x] Migration du code et des ressources d'items vers Minecraft 1.21.11 / NeoForge 21.11.42.
- [x] Sauvegarde du graphe adaptee et protection contre le remplacement d'un fichier reseau illisible.
- [x] Premier durcissement des paquets et du dashboard : controles serveur, validation des hotes/origines et limites de requetes. Le dashboard fonctionne sans authentification ; son acces reseau doit etre restreint.
- [x] IP mobiles persistantes par joueur ; agregation limitee a l'antenne de service.
- [x] Premiers caches bornes et suppression des recalculs inutiles lors de la restauration des blocs connus.
- [x] Build du dashboard reproductible, documentation de lancement et suites de tests ajoutees.

Derniere validation du lot initial : 48 tests JUnit, 4 tests dashboard et 4 GameTests Telecom reussis, plus un test vanilla. La recette visuelle, le multijoueur interactif et les performances sur de grands reseaux restent a verifier.

**Lot couverture web :** moteur radio partage, grille coloree, filtres, hauteur de reception, etats inconnus et calcul progressif implementes. Tests de mur pose/retire, commandes strictes, limites de file, cache et coherence du moteur ajoutes. La carte en jeu et les faisceaux hertziens ne font pas partie de ce lot.

## 3. Ordre de realisation

| Priorite | Objectif | Resultat attendu |
| --- | --- | --- |
| 1 | Carte de couverture calculee | Voir ou une antenne couvre reellement le terrain et les zones masquees par les obstacles. |
| 2 | Fiabilite et debits | Conserver les IP fixes et obtenir des debits coherents, y compris avec plusieurs utilisateurs. |
| 3 | Construction du reseau fixe | Raccorder une habitation par une chaine fibre complete et des ports identifiables. |
| 4 | Mobile et Wi-Fi | Configurer ses antennes et points d'acces, puis se deplacer entre leurs couvertures. |
| 5 | Utilisation | Echanger des messages, utiliser des services internes et commander des equipements distants. |

La securite, les tests et l'optimisation accompagnent chaque priorite ; ils ne sont pas repousses a la fin.

## 4. Priorite : couverture calculee sur la carte

### Fond de carte

- [x] Selectionner une zone dans le dashboard et lancer explicitement sa preparation terrain ou son calcul radio, avec progression, annulation et limites de taille.

- [x] Conserver chaque tuile de terrain sur disque dans le monde apres sa premiere creation, sans expiration ni recalcul, meme apres redemarrage.
- [x] Garder les caches RAM limites et separer les images des differents mondes. Le fond reste fige ; seule la couche de couverture radio suit les modifications des antennes et des obstacles.
- [x] Construire progressivement une seule image globale a partir des PNG captures, sans recalculer le terrain. Un telechargement du fond par revision, aucun au zoom ou au deplacement ; resolution bornee pour eviter une image demesuree.

**Objectif : une couche coloree issue du meme moteur radio que le smartphone, pas un simple cercle autour de l'antenne et pas uniquement les endroits parcourus par le joueur.**

Minecraft fournit le terrain et les blocs ; le moteur radio du mod calcule leur effet sur le signal. Le resultat reste une simulation adaptee au jeu, pas un calcul electromagnetique exact.

### Affichage et comportement

- [x] Ajouter un calque "Couverture calculee" sur la carte web.
- [ ] Ajouter ce calque a la carte en jeu avec les memes donnees.
- [x] Calculer la reception avec `SignalPropagator` partage entre smartphone et carte : distance, frequence, relief, blocs traverses, categories de materiaux et epaisseur des obstacles.
- [x] Afficher les zones en signal fort, moyen, faible ou absent, avec legende ; proposer une antenne selectionnee ou l'ensemble du reseau, et des filtres par technologie/bande.
- [x] Conserver des resultats independants 2G/3G/4G/5G et proposer les pas 1/8/16 blocs. Partager les parcours d'obstacles entre bandes et reutiliser les resultats au changement de technologie.
- [x] Calculer par defaut a hauteur de reception au-dessus du sol ; permettre une hauteur Y choisie pour examiner un etage, un tunnel ou une zone interieure. Indiquer cette hauteur et la precision de la grille.
- [x] Au survol, afficher le signal estime, la technologie et l'antenne retenue. Distinguer reception radio et presence d'un chemin vers un serveur si l'antenne n'est plus raccordee.
- [x] Invalider les calculs concernes apres pose/retrait d'une antenne, modification de ses bandes ou construction/destruction d'obstacles, y compris sans notification des voisins.
- [ ] Integrer orientation et puissance dans la couverture quand ces reglages seront disponibles.
- [ ] Conserver le calque des releves smartphone separement, avec leur date, pour comparer mesures et couverture calculee.

### Calcul sans ralentir le jeu

- [x] Calculer progressivement par tuiles, en priorite dans la zone visible ; limiter la precision selon le zoom et reutiliser les resultats en cache.
- [x] Invalider les tuiles affectees lorsqu'un obstacle change, meme s'il se situe entre l'antenne et la zone affichee ; rejeter les resultats issus d'une ancienne configuration.
- [x] Ne pas charger ou generer massivement des chunks pour la carte. Si le terrain du trajet manque, afficher "inconnu / donnees anciennes", jamais supposer de l'air ou annoncer une absence de signal certaine.
- [x] Conserver le terrain radio 3D observe dans un cache borne et persistant pour calculer aussi a travers les chunks decharges ; garder les donnees chargees prioritaires et les trajets jamais observes inconnus.
- [ ] Lire le monde uniquement sur le thread serveur ; effectuer les calculs lourds sur des donnees detachees, avec files et caches limites.

**Validation :** un mur ou une montagne modifie la couverture derriere l'obstacle ; retirer le mur actualise la zone ; le smartphone et la carte concordent au meme point, a la meme hauteur et avec les memes reglages. Dezoomer ne doit pas bloquer le serveur.

**Limites du lot actuel :** carte web de l'Overworld, pas souhaite 1/8/16 blocs avec apercu plus grossier au grand dezoom, portee maximale commune de 4 096 blocs, formes complexes simplifiees. Le pas effectif est affiche ; le mode bloc par bloc demande un zoom rapproche. Calcul fractionne (budget cooperatif de 2 ms/tick), 256 points maximum par tuile, parcours partages entre bandes et cache commun aux technologies. Les benchmarks a grande echelle restent a faire.

## 5. Mise en place du reseau

### Reseau fixe

- [ ] Stabiliser les IP fixes apres recalcul et redemarrage, sans collisions.
- [ ] Ajouter ports, brassage et fibres independantes dans un cable ; un croisement de cables ne doit pas tout connecter automatiquement.
- [ ] Completer la chaine **NRO avec OLT -> PM -> PBO -> PTO -> ONT -> box**, avec des fonctions distinctes et une configuration simple.
- [ ] Gerer GPON/XGS-PON, partage de capacite et pertes optiques, avec une explication claire quand une liaison ne fonctionne pas.
- [ ] Ameliorer le cuivre : NRA/DSLAM, SR, ligne et modem ADSL/VDSL ; faire dependre le debit de la longueur et de la qualite de ligne.
- [ ] Ajouter poteaux, fourreaux, boitiers et outils de pose assistee pour construire facilement en aerien, en sous-sol et dans les batiments.
- [ ] Permettre des liaisons de collecte et de secours, par cable ou faisceau hertzien.

### Liaisons hertziennes entre sites

Un faisceau hertzien est une liaison radio directionnelle point a point, utilisee notamment pour raccorder un site mobile sans tirer de fibre jusqu'a lui. Il transporte le trafic entre les sites ; il ne remplace pas les antennes qui couvrent les telephones.

```text
Reseau filaire -> Site A [radio FH] ~~~ liaison sans fil ~~~ [radio FH] Site B -> Antenne mobile B
```

- [ ] Ajouter une paire de radios/paraboles directionnelles, distinctes des antennes mobiles, a installer et orienter sur les sites A et B. Seuls les raccordements locaux aux equipements restent necessaires.
- [ ] Permettre l'association des deux extremites et le choix d'un canal compatible ; calculer la qualite selon distance, alignement, frequence et obstacles du monde. Verifier aussi le degagement autour du trajet (zone de Fresnel simplifiee), pas seulement une ligne sans bloc.
- [ ] Integrer la liaison au graphe avec capacite partagee, latence et etat reel : les utilisateurs de B restent limites par le debit du faisceau et par le reseau en amont de A.
- [ ] Permettre des relais A -> B -> C pour contourner un obstacle, sans creer de connexion independante au reseau : chaque saut ajoute ses limites et dependances.
- [ ] Afficher le faisceau sur la carte avec ses extremites, son debit et les obstacles bloquants ; reutiliser les donnees de terrain du moteur radio avec un profil adapte aux liaisons directionnelles.

**Validation :** B fournit un service sans cable entre A et B lorsque le faisceau et le chemin amont fonctionnent. Un obstacle ou un desalignement suffisant degrade ou coupe la liaison. Si le faisceau est coupe, B peut encore emettre un signal mobile, mais les services dependants de cette collecte deviennent indisponibles, sauf chemin de secours.

### Mobile et reseau local

- [ ] Ajouter secteurs d'antenne, orientation, inclinaison, puissance et largeur de bande ; ces reglages doivent agir sur la reception et sur la carte.
- [ ] Partager correctement la capacite radio entre les telephones et tenir compte des interferences et de la liaison de collecte.
- [ ] Ameliorer le choix de l'antenne et le passage entre cellules en mouvement, sans basculements permanents ; distinguer 4G, 5G NSA et SA de facon accessible.
- [ ] Permettre des reseaux mobiles nommes et une association simple du telephone a son reseau, sans abonnement payant ni gestion commerciale.
- [ ] Ajouter switches, ports Ethernet et points d'acces Wi-Fi avec SSID, mot de passe, canaux et attenuation par les murs.
- [ ] Fournir une configuration IP automatique fonctionnelle, puis des reglages manuels optionnels : DHCP, DNS, IPv6, VLAN et routage simple.

## 6. Utilisation du reseau

- [ ] Unifier les debits montants/descendants et le partage des liens : cables, ports, antennes et interfaces doivent annoncer les memes capacites que le moteur.
- [ ] Refaire le speedtest sur telephone et routeur : interface plus lisible, courbes de debit, progression claire des phases et bilan final avec debits moyens, pics, ping, gigue et pertes mesures par le moteur.
- [ ] Permettre de choisir le serveur de speedtest depuis le telephone, le routeur et le dashboard : liste des serveurs avec nom, identifiant, latence estimee et disponibilite, plus un mode automatique.
- [ ] Tester reellement vers le serveur choisi : verifier le chemin cote serveur, afficher la destination utilisee, expliquer les limites du trajet et signaler une indisponibilite sans basculer silencieusement sur un autre serveur.
- [ ] Clarifier la duree totale du test, permettre son annulation et conserver un historique recent par appareil, sans perturber les tests simultanes sur les autres appareils.
- [x] Autoriser plusieurs speedtests simultanes sur des appareils distincts, avec un test par appareil, un suivi independant et le partage des liens communs.
- [ ] Implementer SMS, contacts et numeros entre joueurs, puis appels si une integration vocale adaptee est disponible.
- [ ] Ajouter des services internes au monde : petites pages hebergees, messagerie et transferts de fichiers virtuels, sans acces arbitraire au vrai Internet.
- [ ] Ajouter capteurs, affichages et commandes redstone a distance dont le fonctionnement depend du reseau.
- [ ] Garder un outil simple pour voir le trajet d'une connexion et comprendre une coupure, un mauvais brassage ou une saturation, sans systeme de tickets.
- [ ] Ameliorer la carte : recherche d'equipements, noms, liens physiques, debits et filtres lisibles.

## 7. Qualite et optimisation a maintenir

- [ ] Remplacer les recalculs globaux par des mises a jour locales ; compresser les longs chemins de cables et rechercher seulement les antennes proches.
- [ ] Conserver le reseau logique lorsque les chunks se dechargent, sans maintenir tout le monde charge ; distinguer cet etat des donnees de terrain indisponibles pour la couverture.
- [ ] Limiter memoire, sessions, historique, caches et donnees envoyees aux cartes ; afficher les calculs en attente plutot que figer le jeu.
- [ ] Mesurer le cout du mod sur petit reseau, ville et forte charge, en particulier avec plusieurs cartes de couverture ouvertes.
- [ ] Verifier visuellement le client, le multijoueur, les sauvegardes et le redemarrage complet ; ajouter un test pour chaque correction importante.
- [ ] Conserver les controles d'acces et validations serveur, avec une protection simple contre la modification du reseau d'un autre joueur.
- [ ] Completer traductions, recettes et butin ; fournir des interfaces et un guide faciles a utiliser, sans imposer les reglages experts.

**Une fonctionnalite est terminee lorsqu'elle fonctionne en jeu, utilise les memes regles que les outils de mesure, conserve ses donnees et reste fluide dans les scenarios testes.** La couverture web est maintenant disponible ; les autres cases ouvertes restent le travail a venir.
