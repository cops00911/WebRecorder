-For Run Framework level Typescript Test Run
cd typescript-tests
.\run-tests.bat

-For Run Recorder
mvn exec:java

-For Specific Test
cd typescript-tests
npx.cmd playwright test tests/Managment.spec.ts --headed